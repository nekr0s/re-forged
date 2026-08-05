/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.gui;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import forge.PlayerMat;
import forge.localinstance.properties.ForgeConstants;

/**
 * The image play mats shipped in {@code res/playmats}, listed alongside the
 * {@link PlayerMat} preset colours wherever a mat is picked.
 * <p>
 * A mat's key is its file name without the extension, which is what {@code PlayerMat.fromKey}
 * returns {@code null} for — so "not a preset colour" and "look here instead" line up. Keys
 * travel over the network, and a machine that doesn't have the file simply falls back to the
 * default colour rather than failing.
 * <p>
 * Holds no image data: decoding is left to each GUI, since this class is shared with builds
 * that have no AWT.
 */
public final class PlayMats {
    private static final String[] EXTENSIONS = {".png", ".jpg", ".jpeg"};

    private static List<PlayMat> mats;

    private PlayMats() { }

    /** One image mat: the file, plus the key and label used to pick it. */
    public static final class PlayMat {
        private final String key;
        private final String label;
        private final File file;

        private PlayMat(final String key, final String label, final File file) {
            this.key = key;
            this.label = label;
            this.file = file;
        }

        /** Stored in preferences and sent to other players. */
        public String getKey() { return key; }
        /** Human-readable name for pickers, derived from the file name. */
        public String getLabel() { return label; }
        public File getFile() { return file; }
    }

    /** Every image mat found, by label. Scanned once; empty if the folder is missing. */
    public static synchronized List<PlayMat> getAll() {
        if (mats == null) {
            mats = scan();
        }
        return mats;
    }

    /**
     * @param key a mat key, case-insensitive
     * @return the matching image mat, or {@code null} if the key is empty or names something
     *         that isn't a file in the play mats folder (e.g. a {@link PlayerMat} colour)
     */
    public static PlayMat fromKey(final String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (final PlayMat mat : getAll()) {
            if (mat.getKey().equalsIgnoreCase(key)) {
                return mat;
            }
        }
        return null;
    }

    private static List<PlayMat> scan() {
        final List<PlayMat> found = new ArrayList<>();
        final File[] files = new File(ForgeConstants.PLAYMATS_DIR).listFiles();
        if (files == null) {
            return found;
        }
        for (final File file : files) {
            if (!file.isFile()) {
                continue;
            }
            final String name = file.getName();
            final int dot = name.lastIndexOf('.');
            if (dot <= 0 || !isImage(name.substring(dot))) {
                continue;
            }
            final String key = name.substring(0, dot).toLowerCase(Locale.ENGLISH);
            found.add(new PlayMat(key, labelFor(key), file));
        }
        found.sort(Comparator.comparing(PlayMat::getLabel));
        return found;
    }

    private static boolean isImage(final String extension) {
        return Arrays.stream(EXTENSIONS).anyMatch(extension::equalsIgnoreCase);
    }

    /** {@code dragon_keep} reads as "Dragon Keep" in the picker. */
    private static String labelFor(final String key) {
        final StringBuilder sb = new StringBuilder();
        for (final String word : key.split("[_-]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.length() == 0 ? key : sb.toString();
    }
}
