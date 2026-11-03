package net.gdragflip.util;

import java.util.Locale;

/** 解析 660m / 1.2b / 660k / 660000000 形式的价格。 */
public final class PriceParser {
    private PriceParser() {
    }

    /** @return 金币数；格式非法或非正数时返回 null。 */
    public static Long parse(String text) {
        if (text == null) {
            return null;
        }

        String value = text.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
        if (value.isEmpty()) {
            return null;
        }

        double multiplier = 1.0;
        char suffix = value.charAt(value.length() - 1);
        if (suffix == 'k' || suffix == 'm' || suffix == 'b') {
            multiplier = suffix == 'k' ? 1_000.0 : suffix == 'm' ? 1_000_000.0 : 1_000_000_000.0;
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            return null;
        }

        try {
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number) || number <= 0) {
                return null;
            }
            long result = Math.round(number * multiplier);
            return result > 0 ? result : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
