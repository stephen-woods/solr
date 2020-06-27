/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.api;

import java.io.Closeable;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.analysis.util.ResourceLoaderAware;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.beans.PluginMeta;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.annotation.JsonProperty;
import org.apache.solr.common.cloud.ClusterPropertiesListener;
import org.apache.solr.common.util.Pair;
import org.apache.solr.common.util.PathTrie;
import org.apache.solr.common.util.ReflectMapWriter;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.handler.admin.ContainerPluginsApi;
import org.apache.solr.pkg.PackageLoader;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.util.SolrJacksonAnnotationInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.lucene.util.IOUtils.closeWhileHandlingException;

public class CustomContainerPlugins implements ClusterPropertiesListener {
  private final ObjectMapper mapper = SolrJacksonAnnotationInspector.createObjectMapper();
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  final CoreContainer coreContainer;
  final ApiBag containerApiBag;

  private final Map<String, ApiInfo> currentPlugins = new HashMap<>();

  @Override
  public boolean onChange(Map<String, Object> properties) {
    refresh();
    return false;
  }
  public CustomContainerPlugins(CoreContainer coreContainer, ApiBag apiBag) {
    this.coreContainer = coreContainer;
    this.containerApiBag = apiBag;
  }

  public synchronized void refresh() {
    Map<String, Object> pluginInfos = null;
    try {
      pluginInfos = ContainerPluginsApi.plugins(coreContainer.zkClientSupplier);
    } catch (IOException e) {
      log.error("Could not read plugins data", e);
      return;
    }
    Map<String,PluginMeta> newState = new HashMap<>(pluginInfos.size());
    for (Map.Entry<String, Object> e : pluginInfos.entrySet()) {
      try {
        newState.put(e.getKey(),
            mapper.readValue(Utils.toJSON(e.getValue()), PluginMeta.class));
      } catch (Exception exp) {
        log.error("Invalid apiInfo configuration :", exp);
      }
    }

    Map<String, PluginMeta> currentState = new HashMap<>();
    for (Map.Entry<String, ApiInfo> e : currentPlugins.entrySet()) {
      currentState.put(e.getKey(), e.getValue().info);
    }
    Map<String, Diff> diff = compareMaps(currentState, newState);
    if (diff == null) return;//nothing has changed
    for (Map.Entry<String, Diff> e : diff.entrySet()) {
      if (e.getValue() == Diff.UNCHANGED) continue;
      if (e.getValue() == Diff.REMOVED) {
        ApiInfo apiInfo = currentPlugins.remove(e.getKey());
        if (apiInfo == null) continue;
        for (ApiHolder holder : apiInfo.holders) {
          Api old = containerApiBag.unregister(holder.api.getEndPoint().method()[0], holder.api.getEndPoint().path()[0]);
          if (old instanceof Closeable) {
            closeWhileHandlingException((Closeable) old);
          }
        }
      } else {
        //ADDED or UPDATED
        PluginMeta info = newState.get(e.getKey());
        ApiInfo apiInfo = null;
        List<String> errs = new ArrayList<>();
        apiInfo = new ApiInfo(info, errs);
        if (!errs.isEmpty()) {
          log.error(StrUtils.join(errs, ','));
          continue;
        }
        try {
          apiInfo.init();
        } catch (Exception exp) {
          log.error("Cannot install apiInfo ", exp);
          continue;
        }
        if (e.getValue() == Diff.ADDED) {
          for (ApiHolder holder : apiInfo.holders) {
            containerApiBag.register(holder, Collections.singletonMap("plugin-name", e.getKey()));
          }
          currentPlugins.put(e.getKey(), apiInfo);
        } else {
          ApiInfo old = currentPlugins.put(e.getKey(), apiInfo);
          List<ApiHolder> replaced = new ArrayList<>();
          for (ApiHolder holder : apiInfo.holders) {
            Api oldApi = containerApiBag.lookup(holder.getPath(),
                holder.getMethod().toString(), null);
            if (oldApi instanceof ApiHolder) {
              replaced.add((ApiHolder) oldApi);
            }
            containerApiBag.register(holder, Collections.singletonMap("plugin-name", e.getKey()));
          }
          if (old != null) {
            for (ApiHolder holder : old.holders) {
              if (replaced.contains(holder)) continue;// this path is present in the new one as well. so it already got replaced
              containerApiBag.unregister(holder.getMethod(), holder.getPath());
            }
            if (old instanceof Closeable) {
              closeWhileHandlingException((Closeable) old);
            }
          }
        }
      }

    }
  }

  private static class ApiHolder extends Api {
    final AnnotatedApi api;

    protected ApiHolder(AnnotatedApi api) {
      super(api);
      this.api = api;
    }

    @Override
    public void call(SolrQueryRequest req, SolrQueryResponse rsp) {
      api.call(req, rsp);
    }

    public String getPath(){
      return api.getEndPoint().path()[0];
    }

    public SolrRequest.METHOD getMethod(){
      return api.getEndPoint().method()[0];

    }
  }

  @SuppressWarnings({"rawtypes"})
  public class ApiInfo implements ReflectMapWriter {
    List<ApiHolder> holders;
    @JsonProperty
    private final PluginMeta info;

    @JsonProperty(value = "package")
    public final String pkg;

    private PackageLoader.Package.Version pkgVersion;
    private Class klas;
    Object instance;


    @SuppressWarnings({"unchecked","rawtypes"})
    public ApiInfo(PluginMeta info, List<String> errs) {
      this.info = info;
      Pair<String, String> klassInfo = org.apache.solr.core.PluginInfo.parseClassName(info.klass);
      pkg = klassInfo.first();
      if (pkg != null) {
        PackageLoader.Package p = coreContainer.getPackageLoader().getPackage(pkg);
        if (p == null) {
          errs.add("Invalid package " + klassInfo.first());
          return;
        }
        this.pkgVersion = p.getVersion(info.version);
        if (pkgVersion == null) {
          errs.add("No such package version:" + pkg + ":" + info.version + " . available versions :" + p.allVersions());
          return;
        }
        try {
          klas = pkgVersion.getLoader().findClass(klassInfo.second(), Object.class);
        } catch (Exception e) {
          log.error("Error loading class", e);
          errs.add("Error loading class " + e.toString());
          return;
        }
      } else {
        try {
          klas = Class.forName(klassInfo.second());
        } catch (ClassNotFoundException e) {
          errs.add("Error loading class " + e.toString());
          return;
        }
        pkgVersion = null;
      }
      if (!Modifier.isPublic(klas.getModifiers())) {
        errs.add("Class must be public and static : " + klas.getName());
        return;
      }

      try {
        List<Api> apis = AnnotatedApi.getApis(klas, null);
        for (Object api : apis) {
          EndPoint endPoint = ((AnnotatedApi) api).getEndPoint();
          if (endPoint.path().length > 1 || endPoint.method().length > 1) {
            errs.add("Only one HTTP method and url supported for each API");
          }
          if (endPoint.method().length != 1 || endPoint.path().length != 1) {
            errs.add("The @EndPint must have exactly one method and path attributes");
          }
          List<String> pathSegments = StrUtils.splitSmart(endPoint.path()[0], '/', true);
          PathTrie.replaceTemplates(pathSegments, Collections.singletonMap("plugin-name", info.name));
          if (V2HttpCall.knownPrefixes.contains(pathSegments.get(0))) {
            errs.add("path must not have a prefix: "+pathSegments.get(0));
          }

        }
      } catch (Exception e) {
        errs.add(e.toString());
      }
      if (!errs.isEmpty()) return;

      Constructor constructor = klas.getConstructors()[0];
      if (constructor.getParameterTypes().length > 1 ||
          (constructor.getParameterTypes().length == 1 && constructor.getParameterTypes()[0] != CoreContainer.class)) {
        errs.add("Must have a no-arg constructor or CoreContainer constructor and it must not be a non static inner class");
        return;
      }
      if (!Modifier.isPublic(constructor.getModifiers())) {
        errs.add("Must have a public constructor ");
        return;
      }
    }

    @SuppressWarnings({"rawtypes"})
    public void init() throws Exception {
      if (this.holders != null) return;
      Constructor constructor = klas.getConstructors()[0];
      if (constructor.getParameterTypes().length == 0) {
        instance = pkgVersion.getLoader().newInstance(klas.getName(), Object.class);
      } else if (constructor.getParameterTypes().length == 1 && constructor.getParameterTypes()[0] == CoreContainer.class) {
        instance = pkgVersion.getLoader().newInstance(
            klas.getName(),
            Object.class,
            new String[0],
            new Class[]{CoreContainer.class},
            new Object[]{coreContainer});
      } else {
        throw new RuntimeException("Must have a no-arg constructor or CoreContainer constructor ");
      }
      if (instance instanceof ResourceLoaderAware) {
        try {
          ((ResourceLoaderAware) instance).inform(pkgVersion.getLoader());
        } catch (IOException e) {
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
        }
      }
      this.holders = new ArrayList<>();
      for (Api api : AnnotatedApi.getApis(instance)) {
        holders.add(new ApiHolder((AnnotatedApi) api));
      }
    }
  }

  public ApiInfo createInfo(PluginMeta info, List<String> errs) {
    return new ApiInfo(info, errs);

  }

  public enum Diff {
    ADDED, REMOVED, UNCHANGED, UPDATED;
  }

  public static Map<String, Diff> compareMaps(Map<String,? extends Object> a, Map<String,? extends Object> b) {
    if(a.isEmpty() && b.isEmpty()) return null;
    Map<String, Diff> result = new HashMap<>(Math.max(a.size(), b.size()));
    a.forEach((k, v) -> {
      Object newVal = b.get(k);
      if (newVal == null) {
        result.put(k, Diff.REMOVED);
        return;
      }
      result.put(k, Objects.equals(v, newVal) ?
          Diff.UNCHANGED :
          Diff.UPDATED);
    });

    b.forEach((k, v) -> {
      if (a.get(k) == null) result.put(k, Diff.ADDED);
    });

    for (Diff value : result.values()) {
      if (value != Diff.UNCHANGED) return result;
    }

    return null;
  }
}
