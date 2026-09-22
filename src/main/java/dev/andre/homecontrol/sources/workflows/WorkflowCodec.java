package dev.andre.homecontrol.sources.workflows;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JSON boundary for encrypted workflow definitions. It never propagates parser excerpts. */
public final class WorkflowCodec {
    private static final int MAX_LENGTH = 16_384;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public String encode(WorkflowDefinition definition) {
        validate(definition);
        try {
            String encoded = JSON.writeValueAsString(definition);
            checkLength(encoded);
            return encoded;
        } catch (JacksonException e) {
            throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "definition could not be serialized");
        }
    }

    public WorkflowDefinition decode(String encoded) {
        checkLength(encoded);
        try {
            JsonNode node = JSON.readTree(encoded);
            if (node == null || !node.isObject() || !node.path("schemaVersion").isInt()
                    || node.path("schemaVersion").intValue() != 1) {
                throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "unsupported definition schema");
            }
            WorkflowDefinition definition = JSON.treeToValue(node, WorkflowDefinition.class);
            validate(definition);
            return definition;
        } catch (WorkflowException e) {
            throw e;
        } catch (JacksonException | IllegalArgumentException e) {
            throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "definition could not be parsed");
        }
    }

    private static void validate(WorkflowDefinition definition) {
        if (definition == null || definition.schemaVersion() != 1) {
            throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "unsupported definition schema");
        }
        if (definition.id() == null || !definition.id().matches("w-[0-9a-f]{12}")) {
            throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "invalid definition ID");
        }
        if (definition.revision() <= 0) {
            throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "invalid definition revision");
        }
        WorkflowValidator.validate(definition.draft());
    }

    private static void checkLength(String encoded) {
        if (encoded == null || encoded.length() > MAX_LENGTH) {
            throw new WorkflowException(WorkflowException.Stage.WORKFLOW, "definition exceeds storage limit");
        }
    }
}
