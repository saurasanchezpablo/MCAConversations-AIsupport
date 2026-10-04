package dev.otectus.mcaconversations.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the villager's own line commits them to. The game holds the villager to it: what they say they
 * will do happens, and what cannot happen they do not get to say. Three readings, all pure:
 * <ul>
 *   <li>{@link #claims}: first-person promises of a specific action ("voy a talar", "te sigo",
 *       "aquí tienes", "I'll go fishing");</li>
 *   <li>{@link #accepts}: a plain yes ("¡claro!", "vale", "sure"), which commits them to whatever the
 *       player just asked;</li>
 *   <li>{@link #refuses}: a no ("no pienso", "lo siento, pero no", "I won't"), so nothing runs on their
 *       behalf.</li>
 * </ul>
 */
final class AiCommitment {

    /** A specific action the line commits to, with its task for work. */
    record Claim(AiActionKind kind, Optional<AiChore> chore) {
    }

    private record Rule(Pattern pattern, AiActionKind kind, AiChore chore) {
    }

    private static final String GO = "(?:voy a|ire a|me pongo a|me voy a|vamos a|ahora (?:mismo )?(?:voy a)?|enseguida|"
            + "en seguida)";

    private static Rule rule(String regex, AiActionKind kind) {
        return new Rule(Pattern.compile(regex), kind, null);
    }

    private static Rule work(String regex, AiChore chore) {
        return new Rule(Pattern.compile(regex), AiActionKind.WORK, chore);
    }

    private static final List<Rule> RULES = List.of(
            work("\\b(" + GO + " ?(talar|cortar (madera|lena|arboles|troncos)|por madera|buscar madera)|talare|"
                    + "cortare (madera|lena|arboles|troncos)|a por madera|i'?ll (go )?(chop|cut|get (you )?(some )?wood)|"
                    + "(i'?m|i am) (going|off) to (chop|cut|get (you )?(some )?wood))\\b", AiChore.CHOP),
            work("\\b(" + GO + " ?(minar|picar|excavar|por piedra|buscar piedra|buscar minerales)|minare|picare|"
                    + "a por piedra|i'?ll (go )?(mine|dig|get (you )?(some )?stone)|(i'?m|i am) (going|off) to (mine|dig))\\b",
                    AiChore.MINE),
            work("\\b(" + GO + " ?pescar|pescare|a pescar|i'?ll (go )?fish|i'?ll go fishing|(i'?m|i am) (going|off) "
                    + "(to fish|fishing))\\b", AiChore.FISH),
            work("\\b(" + GO + " ?cazar|cazare|a cazar|i'?ll (go )?hunt|(i'?m|i am) (going|off) (to hunt|hunting))\\b",
                    AiChore.HUNT),
            work("\\b(" + GO + " ?(cosechar|recolectar|segar)|cosechare|recolectare|i'?ll (go )?harvest|(i'?m|i am) "
                    + "(going|off) to harvest)\\b", AiChore.HARVEST),
            rule("\\b(te sigo|voy contigo|te acompano|vamos juntos|detras de ti|guia(me)? tu|i'?ll follow|lead the way|"
                    + "right behind you|i'?ll come with you)\\b", AiActionKind.FOLLOW),
            rule("\\b(me quedo (aqui|quiet[oa])|te espero aqui|aqui te espero|no me muevo|i'?ll (stay|wait) here|"
                    + "i'?ll stay put)\\b", AiActionKind.STAY),
            rule("\\b(me voy a casa|vuelvo a casa|me marcho a casa|i'?ll (go|head) home|heading home)\\b", AiActionKind.GO_HOME),
            rule("\\b(aqui tienes|te lo doy|te los doy|te las doy|te doy (lo|todo|esto)|te entrego|te (lo|los|las) traigo|"
                    + "ahora te (lo |los |las )?(doy|traigo)|here you go|here you are|take (it|them)|"
                    + "i'?ll (give|bring) (it|them|you))\\b", AiActionKind.GIVE),
            rule("\\b(echa(le)? un (vistazo|ojo) a (mis|lo que)|mira (mis|lo que) (vendo|tengo)|vamos a comerciar|"
                    + "let'?s trade|take a look at (my|what)|here'?s what i (have|sell))\\b", AiActionKind.TRADE),
            rule("\\b(" + GO + " ?(asar|cocinar|fundir|hornear)|asare|cocinare|fundire|horneare|lo (aso|cocino|fundo)|"
                    + "i'?ll (cook|roast|smelt|bake))\\b", AiActionKind.COOK),
            rule("\\b(" + GO + " ?construir|construire|manos a la obra|lo construyo|i'?ll build|let'?s build)\\b",
                    AiActionKind.BUILD),
            rule("\\b(a ver (que|lo que) (tienes|traes)|ensename|muestrame|let'?s see what you (have|got)|show me what)\\b",
                    AiActionKind.GIFT),
            rule("\\b((dejo|paro) de (trabajar|talar|minar|picar|pescar|cazar|cosechar)|ya paro|i'?ll stop)\\b",
                    AiActionKind.STOP_WORK));

    private static final Pattern YES = Pattern.compile("^(claro|vale|de acuerdo|por supuesto|ahora mismo|enseguida|"
            + "en seguida|ya voy|voy|hecho|cuenta conmigo|con gusto|sin problema|como no|faltaria mas|venga|perfecto|"
            + "esta bien|bien|si|sure|of course|okay|ok|alright|all right|right away|on my way|consider it done|will do|"
            + "with pleasure|no problem|gladly|coming|yes|yeah|aye|certainly|absolutely|i'?ll|i will|let me)\\b");
    private static final Pattern NO = Pattern.compile("\\b(no( ,)? (puedo|pienso|quiero|voy a|me apetece|tengo ganas)|"
            + "ni hablar|ni loco|de ninguna manera|ni lo suenes|lo siento,? (pero )?no|me niego|olvidalo|"
            + "i won'?t|i will not|i can'?t|i cannot|no way|not a chance|sorry,? (but )?no|i'?d rather not|i refuse|"
            + "forget it|prefiero no)\\b");
    private static final Pattern NEGATION = Pattern.compile("\\b(no|nunca|jamas|don'?t|won'?t|never|not)\\b");
    /** "Mañana voy a talar" is a plan for another day, not a promise to the player now. */
    private static final Pattern LATER = Pattern.compile("\\b(manana|luego|despues|otro dia|algun dia|mas tarde|"
            + "el (lunes|martes|miercoles|jueves|viernes|sabado|domingo)|tomorrow|later|someday|another day|next week)\\b");

    private AiCommitment() {
    }

    /** Something the villager says they were given: a tool for a task, or just "something". */
    record Received(Optional<AiChore> tool, String word) {
    }

    private static final String TOOL_WORDS = "(hacha|cana( de pescar)?|azada|pico|espada|herramienta|axe|fishing rod|rod|hoe|"
            + "pickaxe|pick|sword|tool)";
    private static final String GIFT_WORDS = "(regalo|presente|obsequio|gift|present)";
    private static final Pattern RECEIVED_TOOL = Pattern.compile("\\b(gracias por (el|la|los|las|tu|tus|este|esta|un|una) "
            + TOOL_WORDS + "|(ya|ahora) (que )?tengo (el|la|un|una|mi|tu) " + TOOL_WORDS + "|con (el|la|este|esta|tu) "
            + TOOL_WORDS + " que me (has )?(dado|diste|prestaste|prestado)|me (has )?(dado|diste|prestaste|prestado|traido|"
            + "trajiste) (el|la|un|una|tu) " + TOOL_WORDS + "|thanks? (you )?for the " + TOOL_WORDS + "|now (that )?i (have|got) "
            + "(an?|the|your) " + TOOL_WORDS + "|with the " + TOOL_WORDS + " you (gave|lent)|you (gave|lent) me (an?|the|your) "
            + TOOL_WORDS + ")\\b");
    private static final Pattern RECEIVED_ANY = Pattern.compile("\\b(gracias por (el|tu|este) " + GIFT_WORDS
            + "|me (has )?(dado|diste|regalado|regalaste|traido|trajiste) (algo|esto|eso)|lo que me (has )?(dado|diste|regalaste)|"
            + "thanks? (you )?for the " + GIFT_WORDS + "|you (gave|brought) me (something|this))\\b");

    /** What the villager claims to have been given in this line, if anything. Pure. */
    static Optional<Received> received(String line) {
        String text = AiIntent.normalise(line);
        Matcher m = RECEIVED_TOOL.matcher(text);
        if (m.find()) {
            String word = m.group();
            return Optional.of(new Received(toolChore(word), word));
        }
        Matcher any = RECEIVED_ANY.matcher(text);
        return any.find() ? Optional.of(new Received(Optional.empty(), any.group())) : Optional.empty();
    }

    private static Optional<AiChore> toolChore(String text) {
        if (text.matches(".*\\b(hacha|axe)\\b.*")) {
            return Optional.of(AiChore.CHOP);
        }
        if (text.matches(".*\\b(pico|pickaxe|pick)\\b.*")) {
            return Optional.of(AiChore.MINE);
        }
        if (text.matches(".*\\b(cana|fishing rod|rod)\\b.*")) {
            return Optional.of(AiChore.FISH);
        }
        if (text.matches(".*\\b(azada|hoe)\\b.*")) {
            return Optional.of(AiChore.HARVEST);
        }
        if (text.matches(".*\\b(espada|sword)\\b.*")) {
            return Optional.of(AiChore.HUNT);
        }
        return Optional.empty();
    }

    /** The specific actions this line commits to. Pure. */
    static Map<AiActionKind, Claim> claims(String line) {
        String text = AiIntent.normalise(line);
        Map<AiActionKind, Claim> out = new LinkedHashMap<>();
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                String before = text.substring(Math.max(0, m.start() - 10), m.start());
                String context = text.substring(Math.max(0, m.start() - 25), Math.min(text.length(), m.end() + 15));
                if (!NEGATION.matcher(before).find() && !LATER.matcher(context).find()) {
                    out.putIfAbsent(rule.kind(), new Claim(rule.kind(), Optional.ofNullable(rule.chore())));
                    break;
                }
            }
        }
        return out;
    }

    /** A plain yes at the start of the line. Pure. */
    static boolean accepts(String line) {
        String text = AiIntent.normalise(line);
        return YES.matcher(text).find() && !refuses(line);
    }

    /** A no anywhere in the opening of the line. Pure. */
    static boolean refuses(String line) {
        String text = AiIntent.normalise(line);
        String opening = text.length() > 80 ? text.substring(0, 80) : text;
        return NO.matcher(opening).find() || opening.matches("^(no|nah|nope)\\b.*");
    }
}
