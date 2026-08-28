package app.practicelens.android;

import com.google.firebase.ai.type.Schema;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FirebaseAiSchemas {
    private FirebaseAiSchemas() {}

    public static Schema interpretationSchema() {
        Map<String, Schema> option = new LinkedHashMap<>();
        option.put("position", Schema.numInt("Zero-based visual order.", false));
        option.put("displayLabel", Schema.str("Visible option label such as A or 1.", false));
        option.put("text", Schema.str("Option text, including wrapped visual lines.", false));

        Map<String, Schema> root = new LinkedHashMap<>();
        root.put("status", Schema.enumeration(Arrays.asList("READY", "RETAKE_REQUIRED", "NOT_MCQ")));
        root.put("questionText", Schema.str("Question text exactly as visually shown, preserving Unicode/math.", false));
        root.put(
            "options",
            Schema.array(
                Schema.obj(option, Arrays.asList("position", "displayLabel", "text")),
                "Two to eight options in visual order.",
                false,
                "options",
                2,
                8
            )
        );
        root.put("confidence", Schema.numDouble("Confidence from 0.0 to 1.0.", false, "double", 0.0, 1.0));
        root.put("retakeReason", Schema.str("Reason crop should be retaken, if applicable.", false));
        root.put("warnings", Schema.array(Schema.str(), "Optional warnings.", false));
        return Schema.obj(root, Arrays.asList("status", "questionText", "options", "confidence"));
    }

    public static Schema evaluationSchema() {
        Map<String, Schema> root = new LinkedHashMap<>();
        root.put("correctOptionId", Schema.str("One supplied app-owned option ID only.", false));
        root.put("explanation", Schema.str("Concise learner-facing explanation without chain-of-thought.", false));
        root.put("confidence", Schema.numDouble("Confidence from 0.0 to 1.0.", false, "double", 0.0, 1.0));
        root.put(
            "uncertain",
            new Schema(
                "boolean",
                "True when unreadable, ambiguous, conflicting, incomplete, or multi-answer.",
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            )
        );
        root.put("warning", Schema.str("Optional learner-facing warning.", false));
        List<String> required = Arrays.asList("correctOptionId", "explanation", "confidence", "uncertain");
        return Schema.obj(root, required);
    }

    public static Schema automaticAnswerSchema() {
        Map<String, Schema> option = new LinkedHashMap<>();
        option.put("position", Schema.numInt("Zero-based visual order.", false));
        option.put("displayLabel", Schema.str("Visible option label such as A or 1.", false));
        option.put("text", Schema.str("Option text, including wrapped visual lines.", false));

        Map<String, Schema> root = new LinkedHashMap<>();
        root.put("status", Schema.enumeration(Arrays.asList("ANSWERED", "UNREADABLE", "NO_SINGLE_MCQ", "UNSUPPORTED")));
        root.put("questionText", Schema.str("Concise text of the dominant visible MCQ.", false));
        root.put(
            "options",
            Schema.array(
                Schema.obj(option, Arrays.asList("position", "displayLabel", "text")),
                "Two to eight visible choices in visual order.",
                false,
                "options",
                2,
                8
            )
        );
        root.put("selectedOptionIndex", Schema.numInt("Zero-based index into options for the selected answer.", false));
        root.put("answerLabel", Schema.str("Visible answer label such as A or 1, if present.", false));
        root.put("answerText", Schema.str("Answer choice text. Required when ANSWERED.", false));
        root.put("explanation", Schema.str("Concise learner-facing explanation. Required when ANSWERED.", false));
        root.put("confidence", Schema.numDouble("Confidence from 0.0 to 1.0.", false, "double", 0.0, 1.0));
        root.put(
            "imageReadable",
            new Schema(
                "boolean",
                "True when the image is readable enough to inspect the question and options.",
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            )
        );
        root.put(
            "answerable",
            new Schema(
                "boolean",
                "True when a single MCQ and answer can be identified without guessing.",
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            )
        );
        return Schema.obj(root, Arrays.asList("status", "confidence", "imageReadable", "answerable"));
    }
}
