package org.openrefine.extensions.files.importer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 件题名「方别 + 当事人」规则渲染。
 *
 * <p>生成式引擎会把「原告赵建明借条」这类方别/当事人直接写进页级题名；非生成式引擎
 * （如 Grounding 的 span 抽取）只给「借条」，且按产品边界不生成原文没有的语义。
 * 本类不再依赖模型补这两项：方别取卷宗封面解析出的 {@code party_roles}（纯规则），
 * 当事人取页级责任者与名单的匹配结果，文种仍取件题名。</p>
 *
 * <p>与 AIMP 侧 {@code extraction_processor.compose_party_titles} 同口径：
 * <ul>
 *   <li>只对当事人材料拼方别+当事人，法院制作文书（判决书/笔录/送达回证…）不挂某一方；</li>
 *   <li>封面双方名单都在且页级责任者为空时不做猜测；</li>
 *   <li>单方名单多人时只补方别、不补具体姓名；</li>
 *   <li>已含方别/人名的题名不重复拼接。</li>
 * </ul>
 */
public final class PartyTitleComposer {

    /** 方别词表：长词优先，避免「被上诉人」被「上诉人」截断。 */
    private static final String[] SIDES = {
            "被上诉人", "上诉人", "被申请人", "申请人", "原告", "被告", "第三人"
    };

    /** 法院制作、对全案生效的文书：件名不带当事人方别（mock 真值卷内目录即如此）。 */
    private static final String[] COURT_MADE_WORDS = {
            "判决书", "裁定书", "调解书", "通知书", "传票", "笔录", "送达回证", "缴费凭证",
            "移送函", "退卷函", "结案登记表", "证物处理手续", "执行手续材料", "司法建议书",
            "公告", "决定书", "证明书", "告知书", "通缉令", "逮捕证", "拘留证", "支付令", "拘传令"
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PartyTitleComposer() {
    }

    /**
     * 为每件补方别/当事人与四级件名（就地修改 {@code pieces}）。
     *
     * @param pieces         件的页区间（页码为卷内列表序号，1 起）
     * @param partiesByPage  按列表序号排列的页级责任者原文，缺失为 null/空串
     * @param partyRolesJson 卷宗封面解析出的原被告名单，形如 {@code {"原告":["赵建明"],"被告":[...]}}
     */
    public static void compose(List<TitleSplitter.Piece> pieces,
                               List<String> partiesByPage,
                               String partyRolesJson) {
        if (pieces == null || pieces.isEmpty()) {
            return;
        }
        Map<String, List<String>> roles = parseRoles(partyRolesJson);
        if (roles.isEmpty()) {
            return;
        }
        for (TitleSplitter.Piece piece : pieces) {
            String title = piece.title == null ? "" : piece.title.trim();
            String side = null;
            String name = "";

            // 1) 件内任一页的页级责任者命中封面名单 → 用命中的那一方与名单写法
            int from = Math.max(1, piece.startPage);
            int to = partiesByPage == null ? 0 : Math.min(partiesByPage.size(), piece.endPage);
            for (int page = from; page <= to && side == null; page++) {
                String raw = trimToEmpty(partiesByPage.get(page - 1));
                if (raw.isEmpty()) {
                    continue;
                }
                for (String candidate : SIDES) {
                    for (String roleName : roles.getOrDefault(candidate, new ArrayList<>())) {
                        if (!roleName.isEmpty() && (raw.contains(roleName) || roleName.contains(raw))) {
                            side = candidate;
                            name = roleName;
                            break;
                        }
                    }
                    if (side != null) {
                        break;
                    }
                }
            }

            // 2) 题名自带方别（「授权委托书（原告）」「原告举证材料」）
            if (side == null) {
                for (String candidate : SIDES) {
                    if (title.contains(candidate)) {
                        side = candidate;
                        List<String> names = roles.getOrDefault(candidate, new ArrayList<>());
                        name = names.size() == 1 ? names.get(0) : "";
                        break;
                    }
                }
            }

            // 3) 封面名单只有一方 → 直接用该方（单方卷，如申请执行、一方缺席）
            if (side == null) {
                List<String> onlySides = new ArrayList<>();
                for (String candidate : SIDES) {
                    if (!roles.getOrDefault(candidate, new ArrayList<>()).isEmpty()) {
                        onlySides.add(candidate);
                    }
                }
                if (onlySides.size() == 1) {
                    side = onlySides.get(0);
                    List<String> names = roles.get(side);
                    name = names.size() == 1 ? names.get(0) : "";
                }
            }

            String docType = stripPartyPrefix(title, side, name);
            if (side != null) {
                piece.partySide = side;
                piece.partyName = name;
                piece.docType = docType;
            }
            if (side != null && !docType.isEmpty() && !isCourtMadeDoc(docType)) {
                piece.level4Title = (side + name + docType).trim();
                piece.titleSource = "mixed";
            } else {
                piece.level4Title = title;
                if (piece.titleSource.isEmpty()) {
                    piece.titleSource = "model";
                }
            }
        }
    }

    /** 落表用的件题名：有规则渲染结果优先，否则回退模型题名。 */
    public static String displayTitle(TitleSplitter.Piece piece) {
        if (piece == null) {
            return "";
        }
        String level4 = trimToEmpty(piece.level4Title);
        return level4.isEmpty() ? trimToEmpty(piece.title) : level4;
    }

    /** 该文种是否由法院制作（对全案生效，件名不应挂某一方当事人）。 */
    public static boolean isCourtMadeDoc(String docType) {
        String text = trimToEmpty(docType);
        for (String word : COURT_MADE_WORDS) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String stripPartyPrefix(String title, String side, String name) {
        String text = trimToEmpty(title);
        for (String value : SIDES) {
            text = text.replace("（" + value + "）", "").replace("(" + value + ")", "");
        }
        if (side != null && text.startsWith(side)) {
            text = text.substring(side.length());
        }
        if (!name.isEmpty() && text.startsWith(name)) {
            text = text.substring(name.length());
        }
        text = text.trim();
        int cut = 0;
        while (cut < text.length() && "的、及与 ".indexOf(text.charAt(cut)) >= 0) {
            cut++;
        }
        text = text.substring(cut);
        return text.isEmpty() ? trimToEmpty(title) : text;
    }

    private static Map<String, List<String>> parseRoles(String partyRolesJson) {
        Map<String, List<String>> roles = new LinkedHashMap<>();
        String text = trimToEmpty(partyRolesJson);
        if (text.isEmpty()) {
            return roles;
        }
        try {
            JsonNode root = MAPPER.readTree(text);
            if (root == null || !root.isObject()) {
                return roles;
            }
            for (String side : SIDES) {
                JsonNode node = root.get(side);
                if (node == null || !node.isArray()) {
                    continue;
                }
                Set<String> names = new LinkedHashSet<>();
                for (JsonNode item : node) {
                    String name = trimToEmpty(item.asText(""));
                    if (!name.isEmpty()) {
                        names.add(name);
                    }
                }
                if (!names.isEmpty()) {
                    roles.put(side, new ArrayList<>(names));
                }
            }
        } catch (Exception ignored) {
            // 名单解析失败时不做规则渲染，保持模型题名
        }
        return roles;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** 供单测使用：方别词表（不可变）。 */
    static List<String> sides() {
        return Arrays.asList(SIDES);
    }
}
