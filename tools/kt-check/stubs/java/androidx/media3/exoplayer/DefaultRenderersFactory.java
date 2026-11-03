package androidx.media3.exoplayer;

import androidx.media3.exoplayer.audio.AudioSink;

/**
 * Stub Java (no va al APK). Tiene que ser Java: `import ...DefaultRenderersFactory.
 * EXTENSION_RENDERER_MODE_OFF` sólo resuelve si las constantes son `static final` de una
 * clase Java, como en Media3 real. Declararlas en Kotlin no compila el mismo import.
 */
public class DefaultRenderersFactory {
    public static final int EXTENSION_RENDERER_MODE_OFF = 0;
    public static final int EXTENSION_RENDERER_MODE_ON = 1;
    public static final int EXTENSION_RENDERER_MODE_PREFER = 2;

    public DefaultRenderersFactory(android.content.Context context) {}

    public DefaultRenderersFactory setExtensionRendererMode(int mode) { return this; }
    public DefaultRenderersFactory setEnableAudioFloatOutput(boolean enabled) { return this; }
    public DefaultRenderersFactory setEnableAudioTrackPlaybackParams(boolean enabled) { return this; }

    protected AudioSink buildAudioSink(
            android.content.Context context,
            boolean enableFloatOutput,
            boolean enableAudioTrackPlaybackParams) {
        return null;
    }
}
