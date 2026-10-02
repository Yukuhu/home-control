package dev.andre.homecontrol.themes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeAssetTest {
    @Test void valueEqualityUsesContentRatherThanArrayIdentity() {
        ThemeAsset first = new ThemeAsset("text/css", new byte[] {1, 2});
        ThemeAsset same = new ThemeAsset("text/css", new byte[] {1, 2});
        assertThat(first).isEqualTo(same).hasSameHashCodeAs(same).hasToString(same.toString())
                .isNotEqualTo(new ThemeAsset("text/css", new byte[] {1, 3}))
                .isNotEqualTo(new ThemeAsset("image/png", new byte[] {1, 2}));
    }

    @Test void neitherConstructorInputNorAccessorCanChangeTheValue() {
        byte[] input = {1, 2};
        ThemeAsset asset = new ThemeAsset("text/css", input);
        input[0] = 3;
        asset.bytes()[1] = 4;
        assertThat(asset.bytes()).containsExactly(1, 2);
    }
}
