/*
 * Copyright 2004-2025 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.engine;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Properties;

import org.h2.api.ErrorCode;
import org.h2.command.dml.SetTypes;
import org.h2.message.DbException;
import org.h2.security.SHA256;
import org.h2.store.fs.FileUtils;
import org.h2.store.fs.encrypt.FilePathEncrypt;
import org.h2.store.fs.rec.FilePathRec;
import org.h2.util.IOUtils;
import org.h2.util.NetworkConnectionInfo;
import org.h2.util.SortedProperties;
import org.h2.util.StringUtils;
import org.h2.util.TimeZoneProvider;
import org.h2.util.Utils;

/**
 * Encapsulates the connection settings, including user name and password.
 */
public class ConnectionInfo implements Cloneable {

    private static final HashSet<String> KNOWN_SETTINGS;

    private static final HashSet<String> IGNORED_BY_PARSER;

    private Properties prop = new Properties();
    private String originalURL;
    private String url;
    private String user;
    private byte[] filePasswordHash;
    private byte[] fileEncryptionKey;
    private byte[] userPasswordHash;

    private TimeZoneProvider timeZone;

    /**
     * The database name
     */
    private String name;
    private String nameNormalized;
    private boolean remote;
    private boolean ssl;
    private boolean persistent;
    private boolean unnamed;

    private NetworkConnectionInfo networkConnectionInfo;

    /**
     * Create a connection info object.
     *
     * @param name the database name (including tags), but without the
     *            "jdbc:h2:" prefix
     */
    public ConnectionInfo(String name) {
        this.name = name;
        this.url = Constants.START_URL + name;
        parseName();
    }

    /**
     * Create a connection info object.
     *
     * @param u the database URL (must start with jdbc:h2:)
     * @param info the connection properties or {@code null}
     * @param user the user name or {@code null}
     * @param password
     *            the password as {@code String} or {@code char[]}, or
     *            {@code null}
     */
    public ConnectionInfo(String u, Properties info, String user, Object password) {
        u = remapURL(u);
        originalURL = url = u;
        if (!u.startsWith(Constants.START_URL)) {
            throw getFormatException();
        }
        if (info != null) {
            readProperties(info);
        }
        if (user != null) {
            prop.put("USER", user);
        }
        if (password != null) {
            prop.put("PASSWORD", password);
        }
        readSettingsFromURL();
        Object timeZoneName = prop.remove("TIME ZONE");
        if (timeZoneName != null) {
            timeZone = TimeZoneProvider.ofId(timeZoneName.toString());
        }
        setUserName(removeProperty("USER", ""));
        name = url.substring(Constants.START_URL.length());
        parseName();
        convertPasswords();
        String recoverTest = removeProperty("RECOVER_TEST", null);
        if (recoverTest != null) {
            FilePathRec.register();
            try {
                Utils.callStaticMethod("org.h2.store.RecoverTester.init", recoverTest);
            } catch (Exception e) {
                throw DbException.convert(e);
            }
            name = "rec:" + name;
        }
    }

    static {
        String[] commonSettings = { //
                "ACCESS_MODE_DATA", "AUTO_RECONNECT", "AUTO_SERVER", "AUTO_SERVER_PORT", //
                "CACHE_TYPE", //
                "DB_CLOSE_ON_EXIT", //
                "FILE_LOCK", //
                "JMX", //
                "NETWORK_TIMEOUT", //
                "OLD_INFORMATION_SCHEMA", "OPEN_NEW", //
                "PAGE_SIZE", //
                "RECOVER", //
        };
        String[] settings = { //
                "AUTHREALM", "AUTHZPWD", "AUTOCOMMIT", //
                "CIPHER", "CREATE", //
                "FORBID_CREATION", //
                "IGNORE_UNKNOWN_SETTINGS", "IFEXISTS", "INIT", //
                "NO_UPGRADE", //
                "PASSWORD", "PASSWORD_HASH", //
                "RECOVER_TEST", //
                "USER" //
        };
        HashSet<String> set = new HashSet<>(128);
        set.addAll(SetTypes.getTypes());
        for (String setting : commonSettings) {
            if (!set.add(setting)) {
                throw DbException.getInternalError(setting);
            }
        }
        for (String setting : settings) {
            if (!set.add(setting)) {
                throw DbException.getInternalError(setting);
            }
        }
        KNOWN_SETTINGS = set;
        settings = new String[] { //
                "ASSERT", //
                "BINARY_COLLATION", //
                "DB_CLOSE_ON_EXIT", //
                "PAGE_STORE", //
                "UUID_COLLATION", //
        };
        set = new HashSet<>(32);
        Collections.addAll(set, commonSettings);
        Collections.addAll(set, settings);
        IGNORED_BY_PARSER = set;
    }

    private static boolean isKnownSetting(String s) {
        return KNOWN_SETTINGS.contains(s);
    }

    /**
     * Returns whether setting with the specified name should be ignored by
     * parser.
     *
     * @param name
     *            the name of the setting
     * @return whether setting with the specified name should be ignored by
     *         parser
     */
    public static boolean isIgnoredByParser(String name) {
        return IGNORED_BY_PARSER.contains(name);
    }

    @Override
    public ConnectionInfo clone() throws CloneNotSupportedException {
        ConnectionInfo clone = (ConnectionInfo) super.clone();
        clone.prop = (Properties) prop.clone();
        clone.filePasswordHash = Utils.cloneByteArray(filePasswordHash);
        clone.fileEncryptionKey = Utils.cloneByteArray(fileEncryptionKey);
        clone.userPasswordHash = Utils.cloneByteArray(userPasswordHash);
        return clone;
    }

    private void parseName() {
        if (".".equals(name)) {
            name = "mem:";
        }
        if (name.startsWith("tcp:")) {
            remote = true;
            name = name.substring("tcp:".length());
        } else if (name.startsWith("ssl:")) {
            remote = true;
            ssl = true;
            name = name.substring("ssl:".length());
        } else if (name.startsWith("mem:")) {
            persistent = false;
            if ("mem:".equals(name)) {
                unnamed = true;
            }
        } else if (name.startsWith("file:")) {
            name = name.substring("file:".length());
            persistent = true;
        } else {
            persistent = true;
        }
        if (persistent && !remote) {
            name = IOUtils.nameSeparatorsToNative(name);
        }
    }

    /**
     * Set the base directory of persistent databases, unless the database is in
     * the user home folder (~).
     *
     * @param dir the new base directory
     */
    public void setBaseDir(String dir) {
        if (persistent) {
            String absDir = FileUtils.unwrap(FileUtils.toRealPath(dir));
            boolean absolute = FileUtils.isAbsolute(name);
            String n;
            String prefix = null;
            if (dir.endsWith(File.separator)) {
                dir = dir.substring(0, dir.length() - 1);
            }
            if (absolute) {
                n = name;
            } else {
                n  = FileUtils.unwrap(name);
                prefix = name.substring(0, name.length() - n.length());
                n = dir + File.separatorChar + n;
            }
            String normalizedName = FileUtils.unwrap(FileUtils.toRealPath(n));
            if (normalizedName.equals(absDir) || !normalizedName.startsWith(absDir)) {
                // database name matches the baseDir or
                // database name is clearly outside of the baseDir
                throw DbException.get(ErrorCode.IO_EXCEPTION_1, normalizedName + " outside " +
                        absDir);
            }
            if (absDir.endsWith("/") || absDir.endsWith("\\")) {
                // no further checks are needed for C:/ and similar
            } else if (normalizedName.charAt(absDir.length()) != '/') {
                // database must be within the directory
                // (with baseDir=/test, the database name must not be
                // /test2/x and not /test2)
                throw DbException.get(ErrorCode.IO_EXCEPTION_1, normalizedName + " outside " +
                        absDir);
            }
            if (!absolute) {
                name = prefix + dir + File.separatorChar + FileUtils.unwrap(name);
            }
        }
    }

    /**
     * Check if this is a remote connection.
     *
     * @return true if it is
     */
    public boolean isRemote() {
        return remote;
    }

    /**
     * Check if the referenced database is persistent.
     *
     * @return true if it is
     */
    public boolean isPersistent() {
        return persistent;
    }

    /**
     * Check if the referenced database is an unnamed in-memory database.
     *
     * @return true if it is
     */
    boolean isUnnamedInMemory() {
        return unnamed;
    }

    private void readProperties(Properties info) {
        Object[] list = info.keySet().toArray();
        DbSettings s = null;
        for (Object k : list) {
            String key = StringUtils.toUpperEnglish(k.toString());
            if (prop.containsKey(key)) {
                throw DbException.get(ErrorCode.DUPLICATE_PROPERTY_1, key);
            }
            Object value = info.get(k);
            if (isKnownSetting(key)) {
                prop.put(key, value);
            } else {
                if (s == null) {
                    s = getDbSettings();
                }
                if (s.containsKey(key)) {
                    prop.put(key, value);
                }
            }
        }
    }

    private void readSettingsFromURL() {
        DbSettings defaultSettings = DbSettings.DEFAULT;
        int idx = url.indexOf(';');
        if (idx >= 0) {
            String settings = url.substring(idx + 1);
            url = url.substring(0, idx);
            String unknownSetting = null;
            String[] list = StringUtils.arraySplit(settings, ';', false);
            for (String setting : list) {
                if (setting.isEmpty()) {
                    continue;
                }
                int equal = setting.indexOf('=');
                if (equal < 0) {
                    throw getFormatException();
                }
                String value = setting.substring(equal + 1);
                String key = setting.substring(0, equal);
                key = StringUtils.toUpperEnglish(key);
                if (isKnownSetting(key) || defaultSettings.containsKey(key)) {
                    String old = prop.getProperty(key);
                    if (old != null && !old.equals(value)) {
                        throw DbException.get(ErrorCode.DUPLICATE_PROPERTY_1, key);
                    }
                    prop.setProperty(key, value);
                } else {
                    unknownSetting = key;
                }
            }
            if (unknownSetting != null //
                    && !Utils.parseBoolean(prop.getProperty("IGNORE_UNKNOWN_SETTINGS"), false, false)) {
                throw DbException.get(ErrorCode.UNSUPPORTED_SETTING_1, unknownSetting);
            }
        }
    }

    private void preservePasswordForAuthentication(Object password) {
        if ((!isRemote() || isSSL()) &&  prop.containsKey("AUTHREALM") && password!=null) {
            prop.put("AUTHZPWD",password instanceof char[] ? new String((char[])password) : password);
        }
    }

    private char[] removePassword() {
        Object p = prop.remove("PASSWORD");
        preservePasswordForAuthentication(p);
        if (p == null) {
            return new char[0];
        } else if (p instanceof char[]) {
            return (char[]) p;
        } else {
            return p.toString().toCharArray();
        }
    }

    /**
     * Split the password property into file password and user password if
     * necessary, and convert them to the internal hash format.
     */
    private void convertPasswords() {
        char[] password = removePassword();
        boolean passwordHash = removeProperty("PASSWORD_HASH", false);
        if (getProperty("CIPHER", null) != null) {
            // split password into (filePassword+' '+userPassword)
            int space = -1;
            for (int i = 0, len = password.length; i < len; i++) {
                if (password[i] == ' ') {
                    space = i;
                    break;
                }
            }
            if (space < 0) {
                throw DbException.get(ErrorCode.WRONG_PASSWORD_FORMAT);
            }
            char[] np = Arrays.copyOfRange(password, space + 1, password.length);
            char[] filePassword = Arrays.copyOf(password, space);
            Arrays.fill(password, (char) 0);
            password = np;
            fileEncryptionKey = FilePathEncrypt.getPasswordBytes(filePassword);
            filePasswordHash = hashPassword(passwordHash, "file", filePassword);
        }
        userPasswordHash = hashPassword(passwordHash, user, password);
    }

    private static byte[] hashPassword(boolean passwordHash, String userName,
            char[] password) {
        if (passwordHash) {
            return StringUtils.convertHexToBytes(new String(password));
        }
        if (userName.isEmpty() && password.length == 0) {
            return new byte[0];
        }
        return SHA256.getKeyPasswordHash(userName, password);
    }

    /**
     * Get a boolean property if it is set and return the value.
     *
     * @param key the property name
     * @param defaultValue the default value
     * @return the value
     */
    public boolean getProperty(String key, boolean defaultValue) {
        return Utils.parseBoolean(getProperty(key, null), defaultValue, false);
    }

    /**
     * Remove a boolean property if it is set and return the value.
     *
     * @param key the property name
     * @param defaultValue the default value
     * @return the value
     */
    public boolean removeProperty(String key, boolean defaultValue) {
        return Utils.parseBoolean(removeProperty(key, null), defaultValue, false);
    }

    /**
     * Remove a String property if it is set and return the value.
     *
     * @param key the property name
     * @param defaultValue the default value
     * @return the value
     */
    String removeProperty(String key, String defaultValue) {
        if (SysProperties.CHECK && !isKnownSetting(key)) {
            throw DbException.getInternalError(key);
        }
        Object x = prop.remove(key);
        return x == null ? defaultValue : x.toString();
    }

    /**
     * Get the unique and normalized database name (excluding settings).
     *
     * @return the database name
     */
    public String getName() {
        if (!persistent) {
            return name;
        }
        if (nameNormalized == null) {
            if (!FileUtils.isAbsolute(name) && !name.contains("./") && !name.contains(".\\") && !name.contains(":/")
                    && !name.contains(":\\")) {
                // the name could start with "./", or
                // it could start with a prefix such as "nioMapped:./"
                // for Windows, the path "\test" is not considered
                // absolute as the drive letter is missing,
                // but we consider it absolute
                throw DbException.get(ErrorCode.URL_RELATIVE_TO_CWD, originalURL);
            }
            String suffix = Constants.SUFFIX_MV_FILE;
            String n = FileUtils.toRealPath(name + suffix);
            String fileName = FileUtils.getName(n);
            if (fileName.length() < suffix.length() + 1) {
                throw DbException.get(ErrorCode.INVALID_DATABASE_NAME_1, name);
            }
            nameNormalized = n.substring(0, n.length() - suffix.length());
        }
        return nameNormalized;
    }

    /**
     * Get the file password hash if it is set.
     *
     * @return the password hash or null
     */
    public byte[] getFilePasswordHash() {
        return filePasswordHash;
    }

    byte[] getFileEncryptionKey() {
        return fileEncryptionKey;
    }

    /**
     * Get the name of the user.
     *
     * @return the user name
     */
    public String getUserName() {
        return user;
    }

    /**
     * Get the user password hash.
     *
     * @return the password hash
     */
    byte[] getUserPasswordHash() {
        return userPasswordHash;
    }

    /**
     * Get the property keys.
     *
     * @return the property keys
     */
    String[] getKeys() {
        return prop.keySet().toArray(new String[prop.size()]);
    }

    /**
     * Get the value of the given property.
     *
     * @param key the property key
     * @return the value as a String
     */
    String getProperty(String key) {
        Object value = prop.get(key);
        if (!(value instanceof String)) {
            return null;
        }
        return value.toString();
    }

    /**
     * Get the value of the given property.
     *
     * @param key the property key
     * @param defaultValue the default value
     * @return the value as a String
     */
    int getProperty(String key, int defaultValue) {
        if (SysProperties.CHECK && !isKnownSetting(key)) {
            throw DbException.getInternalError(key);
        }
        String s = getProperty(key);
        return s == null ? defaultValue : Integer.parseInt(s);
    }

    /**
     * Get the value of the given property.
     *
     * @param key the property key
     * @param defaultValue the default value
     * @return the value as a String
     */
    public String getProperty(String key, String defaultValue) {
        if (SysProperties.CHECK && !isKnownSetting(key)) {
            throw DbException.getInternalError(key);
        }
        String s = getProperty(key);
        return s == null ? defaultValue : s;
    }

    /**
     * Get the value of the given property.
     *
     * @param setting the setting id
     * @param defaultValue the default value
     * @return the value as a String
     */
    String getProperty(int setting, String defaultValue) {
        String key = SetTypes.getTypeName(setting);
        String s = getProperty(key);
        return s == null ? defaultValue : s;
    }

    /**
     * Get the value of the given property.
     *
     * @param setting the setting id
     * @param defaultValue the default value
     * @return the value as an integer
     */
    int getIntProperty(int setting, int defaultValue) {
        String key = SetTypes.getTypeName(setting);
        String s = getProperty(key, null);
        try {
            return s == null ? defaultValue : Integer.decode(s);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Check if this is a remote connection with SSL enabled.
     *
     * @return true if it is
     */
    boolean isSSL() {
        return ssl;
    }

    /**
     * Overwrite the user name. The user name is case-insensitive and stored in
     * uppercase. English conversion is used.
     *
     * @param name the user name
     */
    public void setUserName(String name) {
        this.user = StringUtils.toUpperEnglish(name);
    }

    /**
     * Set the user password hash.
     *
     * @param hash the new hash value
     */
    public void setUserPasswordHash(byte[] hash) {
        this.userPasswordHash = hash;
    }

    /**
     * Set the file password hash.
     *
     * @param hash the new hash value
     */
    public void setFilePasswordHash(byte[] hash) {
        this.filePasswordHash = hash;
    }

    public void setFileEncryptionKey(byte[] key) {
        this.fileEncryptionKey = key;
    }

    /**
     * Overwrite a property.
     *
     * @param key the property name
     * @param value the value
     */
    public void setProperty(String key, String value) {
        // value is null if the value is an object
        if (value != null) {
            prop.setProperty(key, value);
        }
    }

    /**
     * Get the database URL.
     *
     * @return the URL
     */
    public String getURL() {
        return url;
    }

    /**
     * Get the complete original database URL.
     *
     * @return the database URL
     */
    public String getOriginalURL() {
        return originalURL;
    }

    /**
     * Set the original database URL.
     *
     * @param url the database url
     */
    public void setOriginalURL(String url) {
        originalURL = url;
    }

    /**
     * Returns the time zone.
     *
     * @return the time zone
     */
    public TimeZoneProvider getTimeZone() {
        return timeZone;
    }

    /**
     * Generate a URL format exception.
     *
     * @return the exception
     */
    DbException getFormatException() {
        return DbException.get(ErrorCode.URL_FORMAT_ERROR_2, Constants.URL_FORMAT, url);
    }

    /**
     * Switch to server mode, and set the server name and database key.
     *
     * @param serverKey the server name, '/', and the security key
     */
    public void setServerKey(String serverKey) {
        remote = true;
        persistent = false;
        this.name = serverKey;
    }

    /**
     * Returns the network connection information, or {@code null}.
     *
     * @return the network connection information, or {@code null}
     */
    public NetworkConnectionInfo getNetworkConnectionInfo() {
        return networkConnectionInfo;
    }

    /**
     * Sets the network connection information.
     *
     * @param networkConnectionInfo the network connection information
     */
    public void setNetworkConnectionInfo(NetworkConnectionInfo networkConnectionInfo) {
        this.networkConnectionInfo = networkConnectionInfo;
    }

    public DbSettings getDbSettings() {
        DbSettings defaultSettings = DbSettings.DEFAULT;
        HashMap<String, String> s = new HashMap<>(DbSettings.TABLE_SIZE);
        for (Object k : prop.keySet()) {
            String key = k.toString();
            if (!isKnownSetting(key) && defaultSettings.containsKey(key)) {
                s.put(key, prop.getProperty(key));
            }
        }
        return DbSettings.getInstance(s);
    }

    private static String remapURL(String url) {
        String urlMap = SysProperties.URL_MAP;
        if (urlMap != null && !urlMap.isEmpty()) {
            try {
                SortedProperties prop;
                prop = SortedProperties.loadProperties(urlMap);
                String url2 = prop.getProperty(url);
                if (url2 == null) {
                    prop.put(url, "");
                    prop.store(urlMap);
                } else {
                    url2 = url2.trim();
                    if (!url2.isEmpty()) {
                        return url2;
                    }
                }
            } catch (IOException e) {
                throw DbException.convert(e);
            }
        }
        return url;
    }

    /**
     * Clear authentication properties.
     */
    public void cleanAuthenticationInfo() {
        removeProperty("AUTHREALM", false);
        removeProperty("AUTHZPWD", false);
    }
}
