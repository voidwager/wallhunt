package dev.voidwager.wallhunt;

import android.util.Base64;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The two Claude calls: prompt -> Wallhaven queries, then thumbnails -> ranked picks.
 * Schemas are written by hand: the SDK's class-derived schemas call Field.getAnnotatedType(), which ART lacks.
 */
final class Hunter {
    static final String MODEL = "claude-opus-5-5";

    static final class Plan {
        final List<String> queries = new ArrayList<>();
        String look;
    }

    static final class Pick {
        int index;
        String why;
    }

    private static final JsonOutputFormat PLAN_FORMAT = format(object(Map.of(
            "queries", Map.of("type", "array", "items", Map.of("type", "string"),
                    "description", "2 or 3 Wallhaven search queries, most literal first. Each is 1-3 plain keywords "
                            + "that would appear as tags (e.g. 'rain city night', 'minimalist mountains'). "
                            + "No quotes or operators."),
            "look", Map.of("type", "string",
                    "description", "One sentence on what the ideal wallpaper looks like, used to judge candidates."))));

    private static final JsonOutputFormat RANK_FORMAT = format(object(Map.of(
            "picks", Map.of("type", "array",
                    "description", "Up to 5 candidates, best first. Leave out any that clearly miss the request.",
                    "items", object(Map.of(
                            "index", Map.of("type", "integer",
                                    "description", "Candidate number as labelled in the message."),
                            "why", Map.of("type", "string",
                                    "description", "Under 20 words: why this one fits, or its main flaw if it is "
                                            + "only a backup.")))))));

    static final class RefusedException extends Exception {
        RefusedException() { super("Claude declined this request. Try different wording."); }
    }

    private final AnthropicClient client;

    Hunter(String apiKey) {
        client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    Plan plan(String prompt) throws RefusedException {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(4000L)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).format(PLAN_FORMAT).build())
                .system("You turn a phone wallpaper request into searches on wallhaven.cc, whose search "
                        + "matches short tag-like keywords. Long phrases return nothing. Read the mood behind "
                        + "the request, not only its nouns.")
                .addUserMessage(prompt)
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        try {
            JSONObject o = only(client.messages().create(params));
            Plan plan = new Plan();
            JSONArray q = o.getJSONArray("queries");
            for (int i = 0; i < q.length(); i++) plan.queries.add(q.getString(i));
            plan.look = o.getString("look");
            return plan;
        } catch (JSONException e) {
            throw new IllegalStateException("Claude's plan didn't parse", e);
        }
    }

    List<Pick> rank(String prompt, String look, List<Wallhaven.Wall> walls, List<byte[]> thumbs)
            throws RefusedException {
        List<ContentBlockParam> blocks = new ArrayList<>();
        blocks.add(text("Request: " + prompt + "\nIdeal: " + look
                + "\nThese are phone wallpaper candidates, each already cropped exactly as it will appear "
                + "on screen. Judge each as a home screen: it sits behind icons and a clock, so busy detail "
                + "in the middle and top thirds hurts, and a subject cut off by the frame is a flaw."));
        for (int i = 0; i < walls.size(); i++) {
            Wallhaven.Wall w = walls.get(i);
            blocks.add(text("Candidate " + i + " (" + w.resolution + ", " + w.favorites + " favourites)"));
            blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .data(Base64.encodeToString(thumbs.get(i), Base64.NO_WRAP))
                            .build())
                    .build()));
        }
        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(8000L)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).format(RANK_FORMAT).build())
                .addUserMessageOfBlockParams(blocks)
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        List<Pick> out = new ArrayList<>();
        try {
            JSONArray arr = only(client.messages().create(params)).getJSONArray("picks");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Pick p = new Pick();
                p.index = o.getInt("index");
                p.why = o.getString("why");
                if (p.index >= 0 && p.index < walls.size()) out.add(p);
            }
        } catch (JSONException e) {
            throw new IllegalStateException("Claude's ranking didn't parse", e);
        }
        return out;
    }

    private static JSONObject only(Message msg) throws RefusedException, JSONException {
        if (msg.stopReason().isPresent() && StopReason.REFUSAL.equals(msg.stopReason().get())) {
            throw new RefusedException();
        }
        String json = msg.content().stream()
                .flatMap(cb -> cb.text().stream())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Claude returned no answer"))
                .text();
        return new JSONObject(json);
    }

    private static Map<String, Object> object(Map<String, Object> props) {
        return Map.of("type", "object", "properties", props,
                "required", new ArrayList<>(props.keySet()), "additionalProperties", false);
    }

    private static JsonOutputFormat format(Map<String, Object> schema) {
        JsonOutputFormat.Schema.Builder b = JsonOutputFormat.Schema.builder();
        schema.forEach((k, v) -> b.putAdditionalProperty(k, JsonValue.from(v)));
        return JsonOutputFormat.builder().schema(b.build()).build();
    }

    private static ContentBlockParam text(String s) {
        return ContentBlockParam.ofText(TextBlockParam.builder().text(s).build());
    }
}
