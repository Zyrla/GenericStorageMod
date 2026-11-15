// The version the source files are currently set up for (what your IDE sees and edits).
stonecutter active "26.2"

stonecutter parameters {
    replacements {
        // Minecraft renamed ResourceLocation to Identifier in 1.21.11.
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
        }
    }
}
