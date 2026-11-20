package me.kanadex.geometryos.guestruntime;

public final class InstallSessionIdTest {
    public static void main(String[] args) {
        String output = "CgroupMap::FindController called for [21003] failed\n" +
                "Success: created install session [277988601]\n";
        if (!"277988601".equals(InstallSessionId.require(output)))
            throw new AssertionError("Unrelated bracketed process ID was selected");

        try {
            InstallSessionId.require("CgroupMap::FindController called for [21003] failed\n");
            throw new AssertionError("Missing install session was accepted");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().startsWith("Missing package session:")) throw expected;
        }
        System.out.println("install_session_id_parse_complete=1");
    }
}