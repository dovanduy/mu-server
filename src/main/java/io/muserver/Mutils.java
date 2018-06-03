package io.muserver;

import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;

public class Mutils {

    /**
     * The new-line character for the current platform, e.g. <code>\n</code> in Linux or <code>\r\n</code> on Windows.
     */
    public static final String NEWLINE = String.format("%n");

    /**
     * @param value the value to encode
     * @return Returns the UTF-8 URL encoded value
     */
    public static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new MuException("Error encoding " + value, e);
        }
    }

    /**
     * @param value the value to decode
     * @return Returns the UTF-8 URL decoded value
     */
    public static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new MuException("Error encoding " + value, e);
        }
    }

    private Mutils() {}

    /**
     * Copies an input stream to another stream
     * @param from The source of the bytes
     * @param to The destination of the bytes
     * @param bufferSize The size of the byte buffer to use as bytes are copied
     * @throws IOException Thrown if there is a problem reading from or writing to either stream
     */
    public static void copy(InputStream from, OutputStream to, int bufferSize) throws IOException {
        byte[] buffer = new byte[bufferSize];
        int read;
        while ((read = from.read(buffer)) > -1) {
            to.write(buffer, 0, read);
        }
    }

    /**
     * Reads the given input stream into a byte array and closes the input stream
     * @param source The source of the bytes
     * @param bufferSize The size of the byte buffer to use when copying streams
     * @return Returns a byte array
     * @throws IOException If there is an error reading from the stream
     */
    public static byte[] toByteArray(InputStream source, int bufferSize) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        copy(source, baos, bufferSize);
        source.close();
        return baos.toByteArray();
    }

    /**
     * Checks for a null string or string with a length of 9
     * @param val The value to check
     * @return True if the value is null or a zero-length string.
     */
    public static boolean nullOrEmpty(String val) {
        return val == null || val.length() == 0;
    }

    /**
     * Checks that a string is not null and has a length greater than 0.
     * @param val The value to check
     * @return True if the string is 1 or more characters.
     */
    public static boolean hasValue(String val) {
        return !nullOrEmpty(val);
    }


    /**
     * Joins two strings with the given separator, unless the first string ends with the separator, or the second
     * string begins with it. For example, the output <code>one/two</code> would be returned from <code>join("one", "two", "/")</code>
     * or <code>join("one/", "/two", "/")</code> or <code>join("one/", "two", "/")</code> or
     * <code>join("one", "/two", "/")</code>
     * @param one The prefix
     * @param sep The separator to put between the two strings, if it is not there already
     * @param two The suffix
     * @return The joined strings
     */
    public static String join(String one, String sep, String two) {
        one = one == null ? "" : one;
        two = two == null ? "" : two;
        boolean oneEnds = one.endsWith(sep);
        boolean twoStarts = two.startsWith(sep);
        if (oneEnds && twoStarts) {
            return one + two.substring(sep.length());
        } else if (oneEnds || twoStarts) {
            return one + two;
        } else {
            return one + sep + two;
        }
    }

    /**
     * Trims the given string from the given value
     * @param value The value to be trimmed
     * @param toTrim The string to trim
     * @return The value with any occurrences of toTrim removed from the start and end of the value
     */
    public static String trim(String value, String toTrim) {
        int len = toTrim.length();
        while (value.startsWith(toTrim)) {
            value = value.substring(len);
        }
        while (value.endsWith(toTrim)) {
            value = value.substring(0, value.length() - len);
        }
        return value;
    }

    /**
     * Throws an {@link IllegalArgumentException} if the given value is null
     * @param name The name of the variable to check
     * @param value The value to check
     */
    public static void notNull(String name, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(name + " cannot be null");
        }
    }

    /**
     * Gets the canonical path of the given file, or if that throws an exception then gets the absolute path.
     * @param file The file to check
     * @return The canonical or absolute path of the given file
     */
    public static String fullPath(File file) {
        notNull("file", file);
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }
}
