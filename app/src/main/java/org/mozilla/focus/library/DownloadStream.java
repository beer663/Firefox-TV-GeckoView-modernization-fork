/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. https://mozilla.org/MPL/2.0/. */
package org.mozilla.focus.library;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** Saves the existing authenticated response, without issuing another request. */
public final class DownloadStream {
    private DownloadStream() {}

    public static long save(
            InputStream body,
            File destination,
            BooleanSupplier cancelled,
            LongConsumer progress) throws IOException {

        boolean complete = false;
        long total = 0;

        try {
            try (InputStream input = body;
                 FileOutputStream output = new FileOutputStream(destination)) {

                byte[] buffer = new byte[64 * 1024];

                while (true) {
                    if (cancelled.getAsBoolean()) {
                        throw new IOException("Download cancelled");
                    }

                    int count = input.read(buffer);
                    if (count < 0) break;

                    output.write(buffer, 0, count);
                    total += count;
                    progress.accept(total);
                }

                if (cancelled.getAsBoolean()) {
                    throw new IOException("Download cancelled");
                }

                output.flush();
            }

            complete = true;
            return total;
        } finally {
            if (!complete) destination.delete();
        }
    }
}
