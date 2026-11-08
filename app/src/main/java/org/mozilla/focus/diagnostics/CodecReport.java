/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/ */

package org.mozilla.focus.diagnostics;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import java.util.Locale;
import org.mozilla.tv.firefox.BuildConfig;

/** Reports advertised capabilities without changing Gecko preferences. */
public final class CodecReport {
    private CodecReport() {}

    public static String collect() {
        StringBuilder report = new StringBuilder("Device: ")
                .append(Build.MANUFACTURER)
                .append(' ')
                .append(Build.MODEL)
                .append("\nAndroid ")
                .append(Build.VERSION.RELEASE)
                .append(" / API ")
                .append(Build.VERSION.SDK_INT)
                .append("\nABIs: ")
                .append(String.join(", ", Build.SUPPORTED_ABIS))
                .append("\nApp: ")
                .append(BuildConfig.VERSION_NAME)
                .append(" (")
                .append(BuildConfig.VERSION_CODE)
                .append(")")
                .append("\n\nAdvertised decoder capabilities\n")
                .append(
                        "These entries do not identify the decoder Gecko "
                                + "actually uses, guarantee smooth playback, "
                                + "or establish DRM compatibility.\n");

        final MediaCodecInfo[] codecs;

        try {
            codecs = new MediaCodecList(
                    MediaCodecList.ALL_CODECS).getCodecInfos();
        } catch (RuntimeException error) {
            return report.append(
                    "\nCould not query this device's codec list.\n").toString();
        }

        String[][] formats = {
                {"H.264", "video/avc"},
                {"VP9", "video/x-vnd.on2.vp9"},
                {"AV1", "video/av01"}
        };

        int[][] modes = {
                {1280, 720, 30},
                {1920, 1080, 30},
                {1920, 1080, 60},
                {3840, 2160, 30},
                {3840, 2160, 60}
        };

        for (String[] format : formats) {
            report.append("\n").append(format[0]).append("\n");
            int count = 0;

            for (MediaCodecInfo codec : codecs) {
                if (codec.isEncoder()) {
                    continue;
                }

                for (String type : codec.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(format[1])) {
                        continue;
                    }

                    count++;
                    report.append("  ").append(codec.getName()).append('\n');

                    if (Build.VERSION.SDK_INT >= 29) {
                        report.append("    Hardware: ")
                                .append(codec.isHardwareAccelerated())
                                .append("; software-only: ")
                                .append(codec.isSoftwareOnly())
                                .append('\n');
                    } else {
                        report.append(
                                "    Hardware classification unavailable "
                                        + "on this Android version.\n");
                    }

                    try {
                        MediaCodecInfo.CodecCapabilities caps =
                                codec.getCapabilitiesForType(type);

                        report.append("    Secure playback required: ")
                                .append(
                                        caps.isFeatureRequired(
                                                MediaCodecInfo.CodecCapabilities
                                                        .FEATURE_SecurePlayback))
                                .append("; tunneled playback required: ")
                                .append(
                                        caps.isFeatureRequired(
                                                MediaCodecInfo.CodecCapabilities
                                                        .FEATURE_TunneledPlayback))
                                .append('\n');

                        MediaCodecInfo.VideoCapabilities video =
                                caps.getVideoCapabilities();

                        if (video != null) {
                            for (int[] mode : modes) {
                                report.append(
                                        String.format(
                                                Locale.US,
                                                "    %dx%d @ %dfps: %s\n",
                                                mode[0],
                                                mode[1],
                                                mode[2],
                                                video.areSizeAndRateSupported(
                                                        mode[0],
                                                        mode[1],
                                                        mode[2])
                                                        ? "advertised"
                                                        : "not advertised"));
                            }
                        }

                        report.append("    Profile/level values:");

                        for (
                                MediaCodecInfo.CodecProfileLevel profile
                                : caps.profileLevels) {
                            report.append(' ')
                                    .append(profile.profile)
                                    .append('/')
                                    .append(profile.level);
                        }

                        report.append('\n');
                    } catch (RuntimeException error) {
                        report.append(
                                "    Capability details unavailable.\n");
                    }
                }
            }

            if (count == 0) {
                report.append("  No decoder advertised.\n");
            }
        }

        return report.toString();
    }
}
