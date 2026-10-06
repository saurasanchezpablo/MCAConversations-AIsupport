package dev.otectus.mcaconversations.ai;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The player's plain requests, in Spanish and English, as the safety net under the model. */
class AiIntentTest {

    private static AiIntent.Intent of(String message) {
        return AiIntent.detect(message).orElseThrow(() -> new AssertionError("no intent in: " + message));
    }

    private static void none(String message) {
        assertTrue(AiIntent.detect(message).isEmpty(), "should not be an order: " + message);
    }

    @Test
    void workOrdersInSpanish() {
        AiIntent.Intent chop = of("Ve a talar madera, por favor");
        assertEquals(AiActionKind.WORK, chop.kind());
        assertEquals(Optional.of(AiChore.CHOP), chop.chore());
        assertEquals(20, of("¿Puedes conseguirme madera? Tala 20 troncos").amount());
        assertEquals(Optional.of(AiChore.MINE), of("pica piedra").chore());
        assertEquals(Optional.of(AiChore.MINE), of("¿podrías ir a minar un poco?").chore());
        assertEquals(Optional.of(AiChore.FISH), of("vete a pescar").chore());
        assertEquals(Optional.of(AiChore.HUNT), of("caza algo para la cena").chore());
        assertEquals(Optional.of(AiChore.HARVEST), of("cosecha el trigo").chore());
        assertEquals(10, of("tala diez troncos").amount());
        assertEquals(64, of("consigue madera, un stack").amount());
        assertTrue(of("todos, id a talar").everyone());
    }

    @Test
    void workOrdersInEnglish() {
        assertEquals(Optional.of(AiChore.CHOP), of("Could you chop some wood?").chore());
        assertEquals(Optional.of(AiChore.MINE), of("go mine some stone").chore());
        assertEquals(Optional.of(AiChore.FISH), of("go fishing").chore());
    }

    @Test
    void givingAndAskingForTheHaul() {
        assertEquals(AiActionKind.GIFT, of("Toma, te doy esto").kind());
        assertEquals(AiActionKind.GIFT, of("tengo algo para ti").kind());
        assertEquals(AiActionKind.GIFT, of("here, take this").kind());
        AiIntent.Intent give = of("Dame lo que has recogido");
        assertEquals(AiActionKind.GIVE, give.kind());
        assertEquals(AiIntent.ALL, give.item());
        assertEquals(AiActionKind.GIVE, of("tráeme la madera que talaste").kind());
        assertEquals(AiActionKind.GIVE, of("entrégame todo").kind());
        assertEquals(AiActionKind.GIVE, of("give me what you gathered").kind());
    }

    @Test
    void otherOrders() {
        assertEquals(AiActionKind.FOLLOW, of("sígueme").kind());
        assertEquals(AiActionKind.STAY, of("quédate aquí").kind());
        assertEquals(AiActionKind.STOP_WORK, of("ya es suficiente, deja de talar").kind());
        assertEquals(AiActionKind.TRADE, of("quiero comerciar").kind());
        assertEquals(AiActionKind.COOK, of("¿me asas esta carne?").kind());
        AiIntent.Intent hut = of("constrúyeme una cabaña aquí");
        assertEquals(AiActionKind.BUILD, hut.kind());
        assertEquals("hut", hut.item());
        assertEquals("pen", of("haz un corral para las ovejas").item());
    }

    @Test
    void talkIsNotAnOrder() {
        none("¿sabes pescar?");
        none("no tales ese árbol");
        none("hola, ¿qué tal el día?");
        none("do you know how to fish?");
        none("ayer estuve hablando con alguien que dijo que en el bosque se puede talar mucho");
        none("me encantan los tomates");
        none("me gusta pescar");
        none("I like to fish");
        none("mi madre toma café por las mañanas");
        none("do you have this in stock?");
    }

    @Test
    void theParserAcceptsEverythingForAHandOver() {
        JsonObject json = new JsonObject();
        json.addProperty("type", "action");
        json.addProperty("do", "give");
        json.addProperty("item", "all");
        assertEquals(AiIntent.ALL, ((AiEffect.Action) AiReplyParser.parseEffect(json).orElseThrow()).item());
        json.remove("item");
        assertEquals(AiIntent.ALL, ((AiEffect.Action) AiReplyParser.parseEffect(json).orElseThrow()).item());
        json.addProperty("item", "#minecraft:logs");
        assertEquals("#minecraft:logs", ((AiEffect.Action) AiReplyParser.parseEffect(json).orElseThrow()).item());
    }
}
