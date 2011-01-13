/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vogar.target;

import java.io.File;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ResponseCache;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;
import java.util.logging.ConsoleHandler;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * This class resets the VM to a relatively pristine state. Useful to defend
 * against tests that muck with system properties and other global state.
 */
public final class TestEnvironment {

    private final Properties systemProperties;

    private final HostnameVerifier defaultHostnameVerifier;
    private final SSLSocketFactory defaultSSLSocketFactory;

    public TestEnvironment() {
        systemProperties = new Properties();
        systemProperties.putAll(System.getProperties());

        String tmpDir = systemProperties.getProperty("java.io.tmpdir");
        String userHome = systemProperties.getProperty("user.home");
        String userDir = systemProperties.getProperty("user.dir");
        if (tmpDir == null || userHome == null || userDir == null) {
            throw new NullPointerException("java.io.tmpdir=" + tmpDir + ", user.home="
                    + userHome + "user.dir=" + userDir);
        }

        // Require writable java.home and user.dir directories for preferences
        if ("Dalvik".equals(System.getProperty("java.vm.name"))) {
            String javaHome = tmpDir + "/java.home";
            makeDirectory(new File(javaHome));
            systemProperties.put("java.home", javaHome);
        }
        if (userHome.length() == 0) {
            userHome = tmpDir + "/user.home";
            makeDirectory(new File(userHome));
            systemProperties.put("user.home", userHome);
        }

        defaultHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        defaultSSLSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
    }

    private void makeDirectory(File path) {
        boolean success;
        if (!path.exists()) {
            success = path.mkdirs();
        } else if (!path.isDirectory()) {
            success = path.delete() && path.mkdirs();
        } else {
            success = true;
        }

        if (!success) {
            throw new RuntimeException("Failed to make directory " + path);
        }
    }

    public void reset() {
        // System Properties
        Properties propertiesCopy = new Properties();
        propertiesCopy.putAll(systemProperties);
        System.setProperties(propertiesCopy);

        // Localization
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));

        // Preferences
        // Temporarily silence the java.util.prefs logger, which otherwise emits
        // an unactionable warning. See RI bug 4751540.
        Logger loggerToMute = Logger.getLogger("java.util.prefs");
        boolean usedParentHandlers = loggerToMute.getUseParentHandlers();
        loggerToMute.setUseParentHandlers(false);
        try {
            resetPreferences(Preferences.systemRoot());
            resetPreferences(Preferences.userRoot());
        } finally {
            loggerToMute.setUseParentHandlers(usedParentHandlers);
        }

        // HttpURLConnection
        Authenticator.setDefault(null);
        CookieHandler.setDefault(null);
        ResponseCache.setDefault(null);
        HttpsURLConnection.setDefaultHostnameVerifier(defaultHostnameVerifier);
        HttpsURLConnection.setDefaultSSLSocketFactory(defaultSSLSocketFactory);

        // Logging
        LogManager.getLogManager().reset();
        Logger.getLogger("").addHandler(new ConsoleHandler());

        // Cleanup to force CloseGuard warnings etc
        System.gc();
        System.runFinalization();
    }

    private static void resetPreferences(Preferences root) {
        try {
            for (String child : root.childrenNames()) {
                root.node(child).removeNode();
            }
            root.clear();
            root.flush();
        } catch (BackingStoreException e) {
            throw new RuntimeException(e);
        }
    }
}
