package modrinth;

import Launcher.FLCore;
import Launcher.Task;
import Launcher.TaskGroup;
import Launcher.TaskGroupAutoProgress;
import UIL.base.IImage;
import Utils.Core;
import Utils.IO;
import Utils.ListMap;
import Utils.SyncVar;
import Utils.json.Json;
import Utils.json.JsonDict;
import Utils.json.JsonElement;
import Utils.json.JsonList;
import Utils.web.WebResponse;
import Utils.web.sURL;
import minecraft.MCFindEvent;
import minecraft.MCProfileScanner;
import minecraft.MinecraftContent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @since ModrinthSupport 0.2.4
 */
public class ModrinthContent extends MinecraftContent {
    public final ModrinthMarket market;

    public final SyncVar<ModrinthTeam> team = new SyncVar<>();

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
    }

    public ModrinthContent(
            final ModrinthMarket market,
            final JsonDict dict
    ) {
        super(dict.getAsString("slug"), dict.getAsString("author"));
        this.market = market;
        setName(dict.getAsString("title"));
        lowerName = getName().toString().toLowerCase();
        final String iu = dict.getAsStringOrDefault("icon_url", null);
        if (iu != null)
            setIcon(market.getIcon(dict.getAsString("icon_url")));
        setShortDescription(dict.getAsString("description"));
    }

    public ModrinthContent(final ModrinthMarket market, final String id) { super(id, null); this.market = market; }

    public void scan() throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WebResponse r = market.c.open("GET", new sURL("https://api.modrinth.com/v2/project/" + getID()), os, true);
        r.auto();
        if (r.getResponseCode() != 200)
            return;
        final ListMap<String, String> versions = new ListMap<>();
        {
            final JsonDict d = Json.parse(os, StandardCharsets.UTF_8, true).getAsDict();
            final String id = d.getAsString("slug");
            setName(d.getAsString("title"));
            lowerName = getName().toString().toLowerCase();
            setIcon(market.getIcon(d.getAsString("icon_url")));
            setShortDescription(d.getAsString("description"));
            if (getAuthor() == null) {
                os = new ByteArrayOutputStream();
                r = market.c.open("GET", new sURL("https://api.modrinth.com/v2/team/" + d.getAsString("team") + "/members"), os, true);
                r.auto();
                if (r.getResponseCode() != 200)
                    return;
                final ModrinthTeam t = new ModrinthTeam();
                for (final JsonElement e : Json.parse(os, StandardCharsets.UTF_8, true).getAsList()) {
                    final JsonDict m = e.getAsDict();
                    t.add(m.getAsDict("user").getAsString("username"));
                }
                t.update();
                team.set(t);
                setAuthor(t.toString());
            }
            for (final JsonElement i : Core.asReversed(d.getAsList("versions")))
                try {
                    final String v;
                    final String vn;
                    if (i.isDict()) {
                        final JsonDict di = i.getAsDict();
                        v = di.getAsString("id");
                        vn = di.getAsString("version_number");
                    } else {
                        v = i.getAsString();
                        vn = "?";
                    }
                    final File f = new File(market.contentsFolder, id + "/" + v + ".json");
                    if (f.exists())
                        this.versions.add(new ModrinthContent.ModrinthVersion(this, Json.parse(f, "UTF-8").getAsDict()));
                    else {
                        this.versions.add(new ModrinthContent.ModrinthVersion(this, v, vn));
                        versions.put(v, id);
                    }
                } catch (final Exception ex) {
                    ex.printStackTrace();
                }
            save();
        }
        if (versions.isEmpty())
            return;
        final StringBuilder b = new StringBuilder("https://api.modrinth.com/v2/versions?ids=[");
        for (final String ver : versions.keySet())
            b.append('"').append(ver).append("\",");
        os = new ByteArrayOutputStream();
        r = market.c.open("GET", new sURL(b.substring(0, b.length() - 1) + "]"), os, true);
        r.auto();
        if (r.getResponseCode() != 200)
            return;
        for (final JsonElement e : Json.parse(os, StandardCharsets.UTF_8, true).getAsList()) {
            final JsonDict d = e.getAsDict();
            final String v = d.getAsString("id"), id = versions.get(v);
            final File f = new File(market.contentsFolder, id + "/" + v + ".json"), p = f.getParentFile();
            if (!p.exists())
                p.mkdirs();
            Files.write(f.toPath(), d.toString().getBytes(StandardCharsets.UTF_8));
            for (final ModrinthContent.ModrinthVersion ver : this.versions)
                if (ver.id.equals(v)) {
                    ver.from(d);
                    break;
                }
        }
    }

    public void save() throws IOException, NoSuchAlgorithmException {
        final JsonDict d = new JsonDict();
        d.put("slug", getID());
        d.put("title", getName().toString());
        d.put("author", team.get().toString());
        final String iu = market.getURLbyIcon(getIcon());
        if (iu != null)
            d.put("icon_url", iu);
        d.put("description", getShortDescription().toString());

        final JsonList l = new JsonList();
        for (final ModrinthVersion ver : versions)
            l.add(new JsonElement(ver.id));
        d.put("versions", l);

        final File f = new File(market.contentsFolder, getID() + "/info.json");
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

    public static class ModrinthVersion extends MinecraftContentVersion {
        public final ModrinthContent content;
        public final String id;
        public String versionNumber;
        public boolean isVanilla, isFabric, isQuilt, isForge, isNeoForge;

        public List<String> versions;
        public List<ModrinthFile> files;

        public ModrinthVersion(final ModrinthContent content, final String id, final String versionNumber) {
            this.content = content;
            this.id = id;
            this.versionNumber = versionNumber;
            isVanilla = false;
            isFabric = false;
            isQuilt = false;
            isForge = false;
            isNeoForge = false;
            versions = Collections.emptyList();
            files = Collections.emptyList();
        }

        public ModrinthVersion(final ModrinthContent content, final JsonDict dict) {
            this.content = content;
            id = dict.getAsString("id");
            versionNumber = dict.getAsString("version_number");
            from(dict);
        }

        @Override public MinecraftContent getContent() { return content; }

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
            versionNumber = dict.getAsString("version_number");
            JsonList l = dict.getAsList("game_versions");
            final String[] gvl = new String[l.size()];
            for (int i = 0; i < gvl.length; i++)
                gvl[i] = l.get(i).getAsString();
            versions = Arrays.asList(gvl);
            l = dict.getAsList("files");

            for (final JsonElement e : l) {
                final JsonDict d = e.getAsDict();
                if (d.getAsBool("primary")) {
                    files = Collections.singletonList(new ModrinthFile(d));
                    return;
                }
            }

            files = l.isEmpty() ? Collections.emptyList() : Collections.singletonList(new ModrinthFile(l.get(0).getAsDict()));
        }

        @Override public String toString() { return versionNumber; }

        public class ModrinthFile {
            public final String filename, sha512;
            public final sURL url;
            public final int size;

            public ModrinthFile(
                    final String filename,
                    final sURL url,
                    final String sha512,
                    final int size
            ) {
                this.filename = filename;
                this.url = url;
                this.sha512 = sha512;
                this.size = size;
            }

            public ModrinthFile(final JsonDict dict) {
                this.filename = dict.getAsString("filename");
                this.url = new sURL(dict.getAsString("url"));
                this.sha512 = dict.getAsDict("hashes").getAsString("sha512");
                this.size = dict.getAsInt("size");
            }
        }
    }

    public ModrinthVersion getByID(final String id) {
        for (final ModrinthVersion ver : versions)
            if (ver.id.equals(id))
                return ver;
        return null;
    }

    @Override
    public ModrinthVersion filter(final MCFindEvent event) {
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
                    return ver;
            }
        }
        return null;
    }

    @Override
    public void add(final MCFindEvent event, final MCProfileScanner scanner) throws Throwable {
        final ModrinthVersion ver = filter(event);
        if (ver == null)
            throw new Exception("This version is not supported");
        final File f = new File(scanner.home, "modrinth.list.json");
        final JsonList l = f.exists() ? Json.parse(f, "UTF-8").getAsList() : new JsonList();
        final JsonDict d = new JsonDict();
        d.put("slug", getID());
        d.put("version", ver.id);
        l.add(d);
        Files.write(f.toPath(), l.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void remove(final MCFindEvent event, final MCProfileScanner scanner) throws Throwable {
        final File f = new File(scanner.home, "modrinth.list.json");
        final JsonList l = Json.parse(f, "UTF-8").getAsList();
        JsonDict r = null;
        for (final JsonElement e : l) {
            final JsonDict d = e.getAsDict();
            if (d.getAsString("slug").equals(getID())) {
                r = d;
                try {
                    final ModrinthVersion ver = getByID(d.getAsString("version"));
                    if (ver == null) {
                        System.out.println("Unknown version " + d.getAsString("version") + " in " + getID());
                        break;
                    }
                    if (ver.isFabric || ver.isQuilt || ver.isForge || ver.isNeoForge) {
                        final File mods = new File(scanner.home, "mods");
                        for (final ModrinthVersion.ModrinthFile fi : ver.files) {
                            if (fi.filename.startsWith("/") || fi.filename.contains(".."))
                                continue;
                            final File file = new File(mods, fi.filename);
                            if (file.exists())
                                file.delete();
                        }
                    }
                } catch (final Exception ex) {
                    ex.printStackTrace();
                }
                break;
            }
        }
        if (r != null) {
            l.remove(r);
            Files.write(f.toPath(), l.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    protected TaskGroup onDelete() {
        FLCore.unbindMeta(this);
        market.plugin.mcPlugin.removeContent(this);
        market.list.remove(this);
        return new TaskGroupAutoProgress(1) {{
            addTask(new Task() {
                @Override
                public void run() {
                    try {
                        final File f = new File(market.contentsFolder, getID());
                        final File[] l = f.listFiles();
                        if (l != null)
                            for (final File sf : l) {
                                if (sf.isDirectory()) {
                                    final File[] l2 = f.listFiles();
                                    if (l2 != null)
                                        for (final File sf2 : l2)
                                            sf2.delete();
                                }
                                sf.delete();
                            }
                        f.delete();
                    } catch (final Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });
        }};
    }
}