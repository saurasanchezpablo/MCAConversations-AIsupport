package dev.otectus.mcaconversations.ai;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the player plainly asked for, read from their own words (Spanish and English), as a safety net
 * under the model. The model decides, and its answer stands. But when it agreed in words and forgot to
 * attach the action, a request as clear as "ve a talar madera", "toma, te doy esto" or "dame lo que
 * has recogido" still happens, as the player meant it.
 *
 * <p>Deliberately conservative: a request needs an imperative ("tala", "dame", "chop") or a request
 * frame ("¿puedes...?", "ve a...", "could you..."). Questions about ability ("¿sabes pescar?") and
 * negations ("no tales") never match. Pure.
 */
final class AiIntent {

    /** A recognised request. {@code item} is {@link #ALL} for "everything you gathered". */
    record Intent(AiActionKind kind, Optional<AiChore> chore, int amount, String item, boolean everyone) {
    }

    static final String ALL = "all";

    private static final String REQUEST = "(?:puedes|podrias|podria|quieres|querrias|necesito que|me haces el favor de|"
            + "ve a|vete a|anda a|id a|vayan a|vamos a|hazme el favor de|te pido que|could you|can you|would you|will you|"
            + "please|go and|go|i need you to|i want you to|por favor)";

    private record Rule(Pattern pattern, AiActionKind kind, AiChore chore, String item) {
    }

    private static Rule rule(String regex, AiActionKind kind) {
        return new Rule(Pattern.compile(regex), kind, null, "");
    }

    private static Rule work(String regex, AiChore chore) {
        return new Rule(Pattern.compile(regex), AiActionKind.WORK, chore, "");
    }

    private static Rule build(String regex, String template) {
        return new Rule(Pattern.compile(regex), AiActionKind.BUILD, null, template);
    }

    /** In order: the first match wins, so the more specific requests come first. */
    private static final List<Rule> RULES = List.of(
            // handing over what was gathered
            rule("\\b(dame|damelo|damela|damelos|entregame|entregamelo|traeme|traemelo|pasame|dejame|"
                    + "dame todo|devuelveme)\\b.*\\b(lo que|todo|recogid|recolectad|talad|minad|pescad|cazad|cosechad|"
                    + "conseguid|madera|troncos|piedra|roca|pescado|peces|carne|trigo|zanahorias|patatas|cosecha|mineral|"
                    + "hierro|carbon|cobre|oro)", AiActionKind.GIVE),
            rule("\\b(give|hand|bring)\\b.*\\b(me|over)\\b.*\\b(what you|everything|all|the (wood|logs|stone|fish|meat|"
                    + "wheat|crops|ore|haul|loot))", AiActionKind.GIVE),
            // giving the villager something
            rule("\\b(te (doy|regalo|traje|traigo|dejo|presto)|toma|ten esto|quiero (darte|regalarte|prestarte)|"
                    + "tengo (algo|un regalo) para ti|es para ti|un regalo para ti|aqui tienes)\\b", AiActionKind.GIFT),
            rule("\\b(take this|here,? take|have this|this is for you|a gift for you|i (want to |wanna )?(give|lend) you|"
                    + "i brought you|i got you something|here you go)\\b", AiActionKind.GIFT),
            // stopping
            rule("\\b(deja de (trabajar|talar|minar|picar|pescar|cazar|cosechar)|para de (trabajar|talar|minar|picar|"
                    + "pescar|cazar|cosechar)|ya (es )?suficiente|ya basta de trabajar|stop working|that'?s enough "
                    + "(work|wood|stone))\\b", AiActionKind.STOP_WORK),
            // building
            build("\\b(construye|construyeme|constuye|haz|hazme|levanta|monta|build|make)\\b.*\\b(cabana|casa|choza|casita|"
                    + "refugio|hut|house|shed|cabin)\\b", "hut"),
            build("\\b(construye|construyeme|haz|hazme|monta|build|make)\\b.*\\b(corral|redil|cercado|valla|pen|fence|"
                    + "enclosure)\\b", "pen"),
            build("\\b(construye|haz|hazme|monta|enciende|build|make|light)\\b.*\\b(hoguera|fogata|fuego de campamento|"
                    + "campfire|bonfire)\\b", "campfire"),
            build("\\b(construye|haz|hazme|prepara|planta|build|make|plant)\\b.*\\b(huerto|huerta|campo|parcela|cultivo|"
                    + "field|farm plot|garden)\\b", "plot"),
            build("\\b(construye|haz|hazme|traza|build|make)\\b.*\\b(camino|sendero|path|road)\\b", "path"),
            build("\\b(construye|haz|hazme|levanta|build|make)\\b.*\\b(muro|pared|muralla|wall)\\b", "wall"),
            // cooking
            rule("\\b(asa|asame|asas|me asas|cocina|cocinamelo|cocinalo|cocinas|me cocinas|funde|fundeme|fundes|me fundes|"
                    + "hornea|horneame|horneas|cuece|cueces|"
                    + "cook|roast|smelt|bake)\\b", AiActionKind.COOK),
            // work
            work("\\b(tala|talar|talame|talas|me talas|corta (madera|arboles|troncos|lena)|cortar (madera|arboles|troncos|lena)|"
                    + "consigue madera|conseguir madera|trae madera|traer madera|recoge madera|recoger madera|"
                    + "chop|cut (some )?(wood|trees|logs)|get (some )?wood|gather wood|lumber)\\b", AiChore.CHOP),
            work("\\b(mina|minar|pica|picar|excava|excavar|consigue piedra|conseguir piedra|coge piedra|coger piedra|"
                    + "trae piedra|traer piedra|recoge piedra|recoger piedra|busca minerales|buscar minerales|"
                    + "mine|dig|get (some )?(stone|cobblestone|ore))\\b", AiChore.MINE),
            work("\\b(pesca|pescar|pescame|pescas|me pescas|ve a pescar|fish|go fishing|catch (some )?fish)\\b", AiChore.FISH),
            work("\\b(caza|cazar|cazame|cazas|me cazas|hunt|go hunting)\\b", AiChore.HUNT),
            work("\\b(cosecha|cosechar|cosechas|me cosechas|recolecta|recolectar|siega|segar|recoge la cosecha|recoger la cosecha|"
                    + "harvest|reap|farm (the )?(crops|fields))\\b", AiChore.HARVEST),
            // errands
            rule("\\b(recoge|recoger|levanta) (eso|esto|lo|las cosas|los objetos|todo) del suelo|pick (it|that|those|"
                    + "everything) up\\b", AiActionKind.PICK_UP),
            rule("\\b(guarda|guardar|mete|meter) (esto|eso|tus cosas|lo que llevas|todo) en el (cofre|baul)|"
                    + "(store|put) (it|that|everything|your stuff) in the chest\\b", AiActionKind.STORE),
            // moving
            rule("\\b(sigueme|seguidme|ven conmigo|venid conmigo|vienes conmigo|vente conmigo|acompaname|me acompanas|"
                    + "follow me|come with me|are you coming)\\b",
                    AiActionKind.FOLLOW),
            rule("\\b(quedate (aqui|quieto|ahi)|espera(me)? aqui|no te muevas|stay (here|put)|wait here)\\b",
                    AiActionKind.STAY),
            rule("\\b(vete a casa|ve a casa|vuelve a casa|go home)\\b", AiActionKind.GO_HOME),
            rule("\\b(ya puedes irte|puedes irte|puedes marcharte|eres libre|you can go|you'?re free to go|move along)\\b",
                    AiActionKind.MOVE),
            rule("\\b(comerciar|intercambiar|que vendes|tus ofertas|hacer (un )?trato|trade|what do you sell|"
                    + "let'?s trade|your wares)\\b", AiActionKind.TRADE));

    private static final Pattern NUMBER = Pattern.compile("\\b(\\d{1,3})\\b");
    private static final Pattern NEGATION = Pattern.compile("\\b(no|nunca|don'?t|do not|never|ni se te ocurra)\\b");
    private static final Pattern ABILITY = Pattern.compile("\\b(sabes|sabrias|has (talado|pescado|cazado|minado)|"
            + "te gusta|do you know how|can you even|have you ever|do you like)\\b");
    private static final Pattern EVERYONE = Pattern.compile("\\b(todos|todas|todo el mundo|vosotros|ustedes|everyone|"
            + "everybody|all of you|you all)\\b");
    private static final Pattern IMPERATIVE_START = Pattern.compile("^(por favor\\s+)?(tala|corta|consigue|trae|recoge|"
            + "mina|pica|excava|coge|busca|pesca|caza|cosecha|recolecta|siega|asa|cocina|funde|hornea|cuece|construye|"
            + "haz|hazme|levanta|monta|dame|entregame|traeme|pasame|toma|ten|sigueme|ven|quedate|espera|vete|vuelve|"
            + "guarda|mete|chop|cut|get|gather|mine|dig|fish|hunt|harvest|reap|cook|roast|smelt|bake|build|make|give|"
            + "hand|bring|take|follow|come|stay|wait|go|store|put|pick|trade|here|have)\\b");

    private AiIntent() {
    }

    /** Lower case, accents gone, punctuation as spaces. Pure. */
    static String normalise(String message) {
        String s = Normalizer.normalize(message == null ? "" : message, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return s.replaceAll("[¿?¡!.,;:\"()]+", " ").replaceAll("\\s+", " ").trim();
    }

    /** The request in the player's words, if it is a clear one. Pure. */
    static Optional<Intent> detect(String message) {
        String text = normalise(message);
        if (text.isEmpty() || ABILITY.matcher(text).find()) {
            return Optional.empty();
        }
        boolean requested = IMPERATIVE_START.matcher(text).find() || Pattern.compile("\\b" + REQUEST + "\\b").matcher(text).find();
        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(text);
            if (!m.find()) {
                continue;
            }
            // "no tales", "don't chop": a negation just before the request cancels it.
            String before = text.substring(Math.max(0, m.start() - 12), m.start());
            if (NEGATION.matcher(before).find()) {
                return Optional.empty();
            }
            boolean selfEvident = rule.kind() == AiActionKind.GIFT || rule.kind() == AiActionKind.GIVE
                    || rule.kind() == AiActionKind.STOP_WORK || rule.kind() == AiActionKind.FOLLOW
                    || rule.kind() == AiActionKind.STAY || rule.kind() == AiActionKind.TRADE
                    || rule.kind() == AiActionKind.GO_HOME || rule.kind() == AiActionKind.MOVE;
            if (!requested && !selfEvident && m.start() > 20) {
                // A work or build word deep inside a sentence with no request frame: talk, not an order.
                return Optional.empty();
            }
            int amount = rule.kind() == AiActionKind.WORK ? amount(text) : 0;
            String item = rule.kind() == AiActionKind.GIVE ? ALL : rule.item();
            return Optional.of(new Intent(rule.kind(), Optional.ofNullable(rule.chore()), amount, item,
                    EVERYONE.matcher(text).find()));
        }
        return Optional.empty();
    }

    private static final List<String> WORDS = List.of("cero", "un", "dos", "tres", "cuatro", "cinco", "seis", "siete",
            "ocho", "nueve", "diez", "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete", "dieciocho",
            "diecinueve", "veinte");

    /** How much, from digits or number words; 64 for "a stack"; 0 for none. Pure. */
    static int amount(String text) {
        Matcher digits = NUMBER.matcher(text);
        if (digits.find()) {
            return Math.min(256, Integer.parseInt(digits.group(1)));
        }
        if (text.matches(".*\\b(un stack|una pila|un monton|a stack)\\b.*")) {
            return 64;
        }
        for (String w : text.split(" ")) {
            switch (w) {
                case "treinta", "thirty" -> {
                    return 30;
                }
                case "cuarenta", "forty" -> {
                    return 40;
                }
                case "cincuenta", "fifty" -> {
                    return 50;
                }
                case "veinte", "twenty" -> {
                    return 20;
                }
                case "diez", "ten" -> {
                    return 10;
                }
                case "five" -> {
                    return 5;
                }
                default -> {
                    int i = WORDS.indexOf(w);
                    if (i >= 2) {
                        return i;
                    }
                }
            }
        }
        return 0;
    }
}
