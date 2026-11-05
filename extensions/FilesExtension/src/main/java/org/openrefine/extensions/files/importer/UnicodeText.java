package org.openrefine.extensions.files.importer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解码平台发来的 u 转义文本参数。
 *
 * <p>平台（RCP）在发送中文参数前统一做 unicode 转义（反斜杠加 u 的四位十六进制），
 * 避免容器按 GBK/ISO-8859-1 解码表单参数造成乱码（如 "SSO 测试项目" 变成 "SSO 娴嬭瘯椤圭洰"）。
 * 本工具只处理该转义，其它字符原样保留。
 */
public final class UnicodeText {

    private static final Pattern ESCAPED = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    private UnicodeText() {
    }

    public static String decode(String s) {
        if (s == null || s.indexOf("\\u") < 0) {
            return s;
        }
        Matcher m = ESCAPED.matcher(s);
        StringBuilder sb = new StringBuilder(s.length());
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    String.valueOf((char) Integer.parseInt(m.group(1), 16))));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
