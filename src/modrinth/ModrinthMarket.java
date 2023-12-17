package modrinth;

import Launcher.*;
import UIL.Lang;
import UIL.Swing.SSwing;
import UIL.UI;
import UIL.base.IImage;
import UIL.base.LoadingImage;
import Utils.ListMap;
import Utils.SyncVar;
import Utils.json.Json;
import Utils.json.JsonDict;
import Utils.json.JsonElement;
import Utils.json.JsonList;
import Utils.web.WebClient;
import Utils.web.WebResponse;
import Utils.web.sURL;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

public class ModrinthMarket extends Market {
    private static final Object l = new Object();
    private static final ListMap<String, LoadingImage> ICONS = new ListMap<>();
    final WebClient c = new WebClient();

    public final File cache;

    public final ModrinthSupport plugin;

    public final SyncVar<TaskGroupAutoProgress> g = new SyncVar<>();

    public ModrinthMarket(final ModrinthSupport plugin, final IImage icon, final File cacheDir) {
        super("modrinth-support.market", icon);
        this.plugin = plugin;
        c.allowRedirect = true;
        c.headers.put("User-Agent", "FlashLauncher/modrinth.ModrinthSupport/" + plugin.getVersion() + " (mcflashlauncher@gmail.com)");
        cache = new File(cacheDir, "icons");
        if (!cache.exists())
            cache.mkdirs();
        plugin.getContext().addTaskGroup(new TaskGroupAutoProgress() {{
            g.set(this);
            addTask(new Task() {
                @Override
                public void run() {
                    try {
                        final ByteArrayOutputStream os = new ByteArrayOutputStream();
                        final WebResponse r = c.open("GET", new sURL("https://api.modrinth.com/v2/tag/category"), os, true);
                        r.auto();
                        final JsonList l = Json.parse(new InputStreamReader(new ByteArrayInputStream(os.toByteArray()), StandardCharsets.UTF_8), true).getAsList();
                        for (final JsonDict d : l.toArray(new JsonDict[0])) {
                            if (!d.getAsString("header").equals("categories")) {
                                //System.out.println(d.getAsString("project_type") + " = " + d.getAsString("header"));
                                continue;
                            }
                            addCategory(Lang.get("categories." + d.getAsString("name")));
                        }
                    } catch (final Throwable ex) {
                        ex.printStackTrace();
                    }
                }
            });
            addTask(new Task() {
                @Override
                public void run() {
                    try {
                        final File dc = new File(plugin.getPluginData(), "content");
                        if (dc.exists()) {
                            final File[] mods = dc.listFiles();
                            if (mods == null)
                                return;
                            for (final File f : mods) {
                                final JsonDict d = Json.parse(new File(f, "info.json"), "UTF-8").getAsDict();
                                try {
                                    final ModrinthContent content = new ModrinthContent(c,
                                            d.getAsString("slug"),
                                            d.getAsString("title"),
                                            getIcon(d.getAsString("icon_url")),
                                            d.getAsString("author"),
                                            d.getAsString("description")
                                    );
                                    if (FLCore.bindMeta(content))
                                        plugin.mcPlugin.addContent(content);
                                } catch (final Exception ex) {
                                    ex.printStackTrace();
                                }
                            }
                        } else
                            dc.mkdirs();
                    } catch (final Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });
        }});

    }

    @Override
    public void checkForUpdates(final Meta... items) {
        try {
            g.get().waitFinish();
        } catch (final Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public Meta[] find(final String query) {
        final ArrayList<Meta> metas = new ArrayList<>();
        try {
            final ByteArrayOutputStream os = new ByteArrayOutputStream();
            final WebResponse r = c.open("GET", new sURL("https://api.modrinth.com/v2/search" + (query.isEmpty() ? "" : "?query=" + query)), os, true);
            synchronized (l) {
                r.auto();
            }
            //final JsonDict root = Json.parse(new String(os.toByteArray(), StandardCharsets.UTF_8)).getAsDict();
            final JsonDict root = Json.parse(new InputStreamReader(new ByteArrayInputStream(os.toByteArray()), StandardCharsets.UTF_8), true).getAsDict();
            for (final JsonElement e : root.getAsList("hits")) {
                final JsonDict ed = e.getAsDict();
                metas.add(new Meta(ed.getAsString("slug"), null, ed.getAsString("author")) {
                    final LoadingImage icon;
                    final Object[] categories;
                    private final String sd, n;

                    @Override public IImage getIcon() { return icon; }
                    @Override public Object getName() { return n; }
                    @Override public Object[] getCategories() { return categories; }
                    @Override public Object getShortDescription() { return sd; }

                    @Override
                    public TaskGroup install() {
                        final TaskGroupAutoProgress l = new TaskGroupAutoProgress(1);
                        l.addTask(new Task() {
                            @Override
                            public void run() {
                                try {
                                    final ModrinthContent m = new ModrinthContent(
                                            c,
                                            ed.getAsString("slug"),
                                            n,
                                            icon,
                                            ed.getAsString("author"),
                                            sd.toString()
                                    );
                                    if (FLCore.bindMeta(m)) {
                                        plugin.mcPlugin.addContent(m);
                                        final File d = new File(plugin.getPluginData(), "content/" + getID()), f = new File(d, "info.json");
                                        if (!d.exists())
                                            d.mkdirs();
                                        Files.write(f.toPath(), ed.toString().getBytes(StandardCharsets.UTF_8));
                                    }
                                } catch (final Exception ex) {
                                    ex.printStackTrace();
                                }
                            }
                        });
                        return l;
                    }

                    {
                        n = ed.getAsString("title");
                        sd = ed.getAsString("description");
                        final JsonList l = ed.getAsList("display_categories");
                        categories = new Object[l.size()];
                        for (int i = 0; i < categories.length; i++)
                            categories[i] = Lang.get("categories." + l.get(i).getAsString());
                        icon = ModrinthMarket.this.getIcon(ed.getAsString("icon_url"));
                    }
                });
            }
        } catch (final Exception ex) {
            ex.printStackTrace();
        }
        return metas.toArray(new Meta[0]);
    }

    public LoadingImage getIcon(final String iconUrl) {
        if (iconUrl.isEmpty())
            return null;
        else
            synchronized (ICONS) {
                final LoadingImage i = ICONS.get(iconUrl), icon;
                if (i == null) {
                    ICONS.put(iconUrl, icon = new LoadingImage());
                    try {
                        final sURL url = new sURL(iconUrl);
                        final File icoF =
                                url.file.startsWith("/data") ? new File(cache, url.file.substring(5)) :
                                        url.file.startsWith("/") ? new File(cache, url.file.substring(1)) :
                                                new File(cache, url.file);
                        if (icoF.exists())
                            icon.setImage(UI.image(icoF));
                        else
                            new Thread(() -> {
                                try {
                                    final ByteArrayOutputStream os = new ByteArrayOutputStream();
                                    c.open("GET", new sURL(iconUrl), os, true).auto();
                                    final BufferedImage r = new BufferedImage(Meta.ICON_SIZE, Meta.ICON_SIZE, BufferedImage.TYPE_INT_ARGB);

                                    final Graphics2D g = (Graphics2D) r.getGraphics();
                                    g.setRenderingHints(SSwing.RH);
                                    g.drawImage(ImageIO.read(new ByteArrayInputStream(os.toByteArray())), 0, 0, r.getWidth(), r.getHeight(), null);
                                    g.dispose();

                                    icoF.getParentFile().mkdirs();
                                    ImageIO.write(r, "png", icoF);
                                    icon.setImage(UI.image(icoF));
                                } catch (final Exception ex) {
                                    ex.printStackTrace();
                                }
                            }).start();
                    } catch (final Exception ex) {
                        ex.printStackTrace();
                    }
                    return icon;
                } else
                    return i;
            }
    }
}
