// Copyright 2019 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.firebase.firebaseremoteconfig;

import android.content.SharedPreferences;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.remoteconfig.FirebaseRemoteConfig;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigFetchThrottledException;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigInfo;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigValue;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class MethodCallHandlerImpl implements MethodChannel.MethodCallHandler {

  private static final String DEFAULT_PREF_KEY = "default_keys";

  private static SharedPreferences sharedPreferences;

  MethodCallHandlerImpl(SharedPreferences sharedPreferences) {
    MethodCallHandlerImpl.sharedPreferences = sharedPreferences;
  }

  @Override
  public void onMethodCall(MethodCall call, final MethodChannel.Result result) {
    switch (call.method) {
      case "RemoteConfig#instance":
        {
          FirebaseRemoteConfigInfo firebaseRemoteConfigInfo =
              FirebaseRemoteConfig.getInstance().getInfo();

          Map<String, Object> properties = new HashMap<>();
          properties.put("lastFetchTime", firebaseRemoteConfigInfo.getFetchTimeMillis());
          properties.put(
              "lastFetchStatus", mapLastFetchStatus(firebaseRemoteConfigInfo.getLastFetchStatus()));
          properties.put("inDebugMode", false);
          properties.put("parameters", getConfigParameters());
          result.success(properties);
          break;
        }
      case "RemoteConfig#setConfigSettings":
        {
          boolean debugMode = call.argument("debugMode");
          final FirebaseRemoteConfig firebaseRemoteConfig = FirebaseRemoteConfig.getInstance();
          FirebaseRemoteConfigSettings settings = new FirebaseRemoteConfigSettings.Builder().build();
          firebaseRemoteConfig.setConfigSettingsAsync(settings);
          result.success(null);
          break;
        }
      case "RemoteConfig#fetch":
        {
          long expiration = ((Number) call.argument("expiration")).longValue();
          final FirebaseRemoteConfig firebaseRemoteConfig = FirebaseRemoteConfig.getInstance();
          firebaseRemoteConfig
              .fetch(expiration)
              .addOnCompleteListener(
                  new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(Task<Void> task) {
                      FirebaseRemoteConfigInfo firebaseRemoteConfigInfo =
                          firebaseRemoteConfig.getInfo();
                      Map<String, Object> properties = new HashMap<>();
                      properties.put(
                          "lastFetchTime", firebaseRemoteConfigInfo.getFetchTimeMillis());
                      properties.put(
                          "lastFetchStatus",
                          mapLastFetchStatus(firebaseRemoteConfigInfo.getLastFetchStatus()));
                      if (!task.isSuccessful()) {
                        final Exception exception = task.getException();

                        if (exception instanceof FirebaseRemoteConfigFetchThrottledException) {
                          properties.put(
                              "fetchThrottledEnd",
                              ((FirebaseRemoteConfigFetchThrottledException) exception)
                                  .getThrottleEndTimeMillis());
                          String errorMessage =
                              "Fetch has been throttled. See the error's "
                                  + "FETCH_THROTTLED_END field for throttle end time.";
                          result.error("fetchFailedThrottled", errorMessage, properties);
                        } else {
                          String errorMessage =
                              "Unable to complete fetch. Reason is unknown "
                                  + "but this could be due to lack of connectivity.";
                          result.error("fetchFailed", errorMessage, properties);
                        }
                      } else {
                        result.success(properties);
                      }
                    }
                  });
          break;
        }
      case "RemoteConfig#activate":
        {
          FirebaseRemoteConfig.getInstance()
              .activate()
              .addOnCompleteListener(
                  new OnCompleteListener<Boolean>() {
                    @Override
                    public void onComplete(Task<Boolean> task) {
                      if (!task.isSuccessful()) {
                        String errorMessage = "Unable to complete activate.";
                        result.error("activateFailed", errorMessage, null);
                      } else {
                        Map<String, Object> properties = new HashMap<>();
                        properties.put("parameters", getConfigParameters());
                        properties.put("newConfig", task.getResult());
                        result.success(properties);
                      }
                    }
                  });
          break;
        }
      case "RemoteConfig#setDefaults":
        {
          Map<String, Object> defaults = call.argument("defaults");
          final FirebaseRemoteConfig firebaseRemoteConfig = FirebaseRemoteConfig.getInstance();
          firebaseRemoteConfig.setDefaultsAsync(defaults);
          SharedPreferences.Editor editor = sharedPreferences.edit();
          editor.putStringSet(DEFAULT_PREF_KEY, defaults.keySet()).apply();
          result.success(null);
          break;
        }
      default:
        {
          result.notImplemented();
          break;
        }
    }
  }

  private Map<String, Object> getConfigParameters() {
    FirebaseRemoteConfig firebaseRemoteConfig = FirebaseRemoteConfig.getInstance();
    Map<String, Object> parameterMap = new HashMap<>();
    Set<String> keys = firebaseRemoteConfig.getKeysByPrefix("");
    for (String key : keys) {
      FirebaseRemoteConfigValue remoteConfigValue = firebaseRemoteConfig.getValue(key);
      parameterMap.put(key, createRemoteConfigValueMap(remoteConfigValue));
    }
    // Add default parameters if missing since `getKeysByPrefix` does not return default keys.
    Set<String> defaultKeys =
        sharedPreferences.getStringSet(DEFAULT_PREF_KEY, new HashSet<String>());
    for (String defaultKey : defaultKeys) {
      if (!parameterMap.containsKey(defaultKey)) {
        FirebaseRemoteConfigValue remoteConfigValue = firebaseRemoteConfig.getValue(defaultKey);
        parameterMap.put(defaultKey, createRemoteConfigValueMap(remoteConfigValue));
      }
    }
    return parameterMap;
  }

  private Map<String, Object> createRemoteConfigValueMap(
      FirebaseRemoteConfigValue remoteConfigValue) {
    Map<String, Object> valueMap = new HashMap<>();
    valueMap.put("value", remoteConfigValue.asByteArray());
    valueMap.put("source", mapValueSource(remoteConfigValue.getSource()));
    return valueMap;
  }

  private String mapLastFetchStatus(int status) {
    switch (status) {
      case FirebaseRemoteConfig.LAST_FETCH_STATUS_SUCCESS:
        return "success";
      case FirebaseRemoteConfig.LAST_FETCH_STATUS_FAILURE:
        return "failure";
      case FirebaseRemoteConfig.LAST_FETCH_STATUS_THROTTLED:
        return "throttled";
      case FirebaseRemoteConfig.LAST_FETCH_STATUS_NO_FETCH_YET:
        return "noFetchYet";
      default:
        return "failure";
    }
  }

  private String mapValueSource(int source) {
    switch (source) {
      case FirebaseRemoteConfig.VALUE_SOURCE_STATIC:
        return "static";
      case FirebaseRemoteConfig.VALUE_SOURCE_DEFAULT:
        return "default";
      case FirebaseRemoteConfig.VALUE_SOURCE_REMOTE:
        return "remote";
      default:
        return "static";
    }
  }
}
