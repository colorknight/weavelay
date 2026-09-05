package com.weavelay.core.license;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Licence 功能闸门标识。
 */
public final class LicenseFeatures {

    public static final String CONFIRM = "confirm";
    public static final String EXCEL_EXPORT = "excel_export";
    public static final String TEMPLATE_PACK = "template_pack";

    public static final Set<String> ALL = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            CONFIRM, EXCEL_EXPORT, TEMPLATE_PACK)));

    private LicenseFeatures() {}

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
