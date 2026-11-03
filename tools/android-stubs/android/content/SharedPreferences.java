package android.content;

public interface SharedPreferences {
    boolean getBoolean(String key, boolean defValue);
    float getFloat(String key, float defValue);
    int getInt(String key, int defValue);
    long getLong(String key, long defValue);
    String getString(String key, String defValue);
    boolean contains(String key);
    Editor edit();

    interface Editor {
        Editor putBoolean(String key, boolean value);
        Editor putFloat(String key, float value);
        Editor putInt(String key, int value);
        Editor putLong(String key, long value);
        Editor putString(String key, String value);
        boolean commit();
        void apply();
    }
}
