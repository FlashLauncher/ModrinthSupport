package modrinth;

import UIL.base.IImage;
import Utils.json.Json;
import Utils.json.JsonDict;
import Utils.json.JsonElement;
import Utils.web.WebClient;
import Utils.web.WebResponse;
import minecraft.MCFindEvent;
import minecraft.MinecraftContent;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class ModrinthContent extends MinecraftContent {
    public String lowerName;
    public final ArrayList<ModrinthVersion> versions = new ArrayList<>();

    /**
     * @param id Project ID
     * @param author Author of the project
     */
    public ModrinthContent(
            final WebClient client,
            final String id,
            final String name,
            final IImage icon,
            final String author,
            final String shortDescription
    ) throws Exception {
        super(id, author);
        setName(name);
        lowerName = name.toLowerCase();
        setIcon(icon);
        setShortDescription(shortDescription);
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        final WebResponse r = client.open("GET", URI.create("https://api.modrinth.com/v2/project/" + id + "/version"), os, true);
        r.auto();
        if (r.getResponseCode() != 200)
            throw new Exception("[Modrinth] Unknown code: " + r.getResponseCode());
        for (final JsonElement e : Json.parse(new String(os.toByteArray(), StandardCharsets.UTF_8)).getAsList()) {
            final JsonDict d = e.getAsDict();
            final ModrinthVersion ver = new ModrinthVersion();
            ver.id = d.getAsString("id");
            for (final JsonElement se : d.getAsList("loaders")) {
                switch (se.getAsString()) {
                    case "minecraft":
                        ver.isVanilla = true;
                        break;
                    case "fabric":
                        ver.isFabric = true;
                        break;
                    case "quilt":
                        ver.isQuilt = true;
                        break;
                    case "forge":
                        ver.isForge = true;
                        break;
                    case "neoforge":
                        ver.isNeoForge = true;
                        break;
                    default:
                        System.out.println("Unknown loader " + se.getAsString());
                        break;
                }
            }
            for (final JsonElement se : d.getAsList("game_versions"))
                ver.versions.add(se.getAsString());
            versions.add(ver);
        }
    }

    public static class ModrinthVersion {
        public String id;
        public boolean isVanilla = false, isFabric = false, isQuilt = false, isForge = false, isNeoForge = false;
        public final ArrayList<String> versions = new ArrayList<>();
    }

    @Override
    public boolean filter(final MCFindEvent event) {
        if (lowerName.contains(event.search.toLowerCase())) {
            for (final ModrinthVersion ver : versions) {
                if (!ver.versions.contains(event.gameVersion))
                    continue;
                if (
                        (ver.isVanilla && event.loaders.contains("minecraft")) ||
                        (ver.isFabric && event.loaders.contains("fabric")) ||
                        (ver.isQuilt && event.loaders.contains("quilt")) ||
                        (ver.isForge && event.loaders.contains("forge")) ||
                        (ver.isNeoForge && event.loaders.contains("neoforge"))
                )
                    return true;
            }
        }
        return false;
    }
}