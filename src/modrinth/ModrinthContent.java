package modrinth;

import UIL.base.IImage;
import Utils.Core;
import Utils.IO;
import Utils.json.JsonDict;
import Utils.json.JsonElement;
import Utils.json.JsonList;
import minecraft.MCFindEvent;
import minecraft.MinecraftContent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ModrinthContent extends MinecraftContent {
    public final ModrinthMarket market;

    public String lowerName;
    public final ArrayList<ModrinthVersion> versions = new ArrayList<>();

    /**
     * @param id Project ID
     * @param author Author of the project
     */
    public ModrinthContent(
            final ModrinthMarket market,
            final String id,
            final String name,
            final IImage icon,
            final String author,
            final String shortDescription
    ) {
        super(id, author);
        this.market = market;
        setName(name);
        lowerName = name.toLowerCase();
        setIcon(icon);
        setShortDescription(shortDescription);
        /*final ByteArrayOutputStream os = new ByteArrayOutputStream();
        final WebResponse r = market.c.open("GET", URI.create("https://api.modrinth.com/v2/project/" + id + "/version"), os, true);
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
        }*/
    }

    public ModrinthContent(
            final ModrinthMarket market,
            final JsonDict dict
    ) {
        super(dict.getAsString("slug"), dict.getAsString("author"));
        this.market = market;
        setName(dict.getAsString("title"));
        lowerName = getName().toString().toLowerCase();
        setIcon(market.getIcon(dict.getAsString("icon_url")));
        setShortDescription(dict.getAsString("description"));
    }

    public void save() throws IOException, NoSuchAlgorithmException {
        final JsonDict d = new JsonDict();
        d.put("slug", getID());
        d.put("title", getName().toString());
        d.put("author", getAuthor());
        d.put("description", getShortDescription().toString());

        final JsonList l = new JsonList();
        for (final ModrinthVersion ver : versions)
            l.add(new JsonElement(ver.id));
        d.put("versions", l);

        final File f = new File(market.plugin.getPluginData(), "content/" + getID() + "/info.json");
        if (f.exists()) {
            final byte[] data = d.toString().getBytes(StandardCharsets.UTF_8);
            if (!Core.hashToHex("sha-256", data).equals(Core.hashToHex("sha-256", IO.readFully(f))))
                Files.write(f.toPath(), data);
        } else {
            final File p = f.getParentFile();
            if (!p.exists())
                p.mkdirs();
            Files.write(f.toPath(), d.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    public static class ModrinthVersion {
        public final String id;
        public boolean isVanilla, isFabric, isQuilt, isForge, isNeoForge;
        public List<String> versions;

        public ModrinthVersion(final String id) {
            this.id = id;
            isVanilla = false;
            isFabric = false;
            isQuilt = false;
            isForge = false;
            isNeoForge = false;
            versions = new ArrayList<>();
        }

        public ModrinthVersion(final JsonDict dict) { id = dict.getAsString("id"); from(dict); }

        public void from(final JsonDict dict) {
            for (final JsonElement se : dict.getAsList("loaders")) {
                switch (se.getAsString()) {
                    case "minecraft":
                        isVanilla = true;
                        break;
                    case "fabric":
                        isFabric = true;
                        break;
                    case "quilt":
                        isQuilt = true;
                        break;
                    case "forge":
                        isForge = true;
                        break;
                    case "neoforge":
                        isNeoForge = true;
                        break;
                    default:
                        System.out.println("Unknown loader " + se.getAsString());
                        break;
                }
            }
            final JsonList l = dict.getAsList("game_versions");
            final String[] gvl = new String[l.size()];
            for (int i = 0; i < gvl.length; i++)
                gvl[i] = l.get(i).getAsString();
            versions = Arrays.asList(gvl);
        }
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