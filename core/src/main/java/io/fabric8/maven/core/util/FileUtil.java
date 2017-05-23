package io.fabric8.maven.core.util;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * File related methods which cannot be found elsewhere
 * @author roland
 * @since 23.05.17
 */
public class FileUtil {

    public static File getRelativePath(File baseDir, File file) {
        Path baseDirPath = Paths.get(baseDir.getAbsolutePath());
        Path filePath = Paths.get(file.getAbsolutePath());
        return baseDirPath.relativize(filePath).toFile();
    }

    public static String stripPrefix(String text, String prefix) {
        if (text.startsWith(prefix)) {
            return text.substring(prefix.length());
        }
        return text;
    }

    public static String stripPostfix(String text, String postfix) {
        if (text.endsWith(postfix)) {
            return text.substring(text.length() - postfix.length());
        }
        return text;
    }

}


