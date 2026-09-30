package dev.andre.homecontrol.config;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one JSON mapper the application's own code uses: for its data files and for every content source's answers. It
 * refuses input nested deeper than 64 levels or with a number longer than 1,000 characters, so neither a hostile
 * server nor a damaged file can exhaust the stack or the parser. Device protocols in {@code adapters} keep their own.
 */
public final class Json {

    public static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(64).maxNumberLength(1_000).build())
                    .build())
            .build();

    private Json() {
    }
}
