package modrinth;

import Utils.SyncVar;

import java.util.ArrayList;

/**
 * @since ModrinthSupport 0.2.4.1
 */
public class ModrinthTeam {
    private final ArrayList<String> members = new ArrayList<>();
    private final SyncVar<String> s = new SyncVar<>();
    public ModrinthTeam() {}
    public void add(final String member) { synchronized (members) { members.add(member); } }
    public void update() { synchronized (members) { s.set(String.join(", ", members)); } }
    @Override public final String toString() { return s.get(); }
}