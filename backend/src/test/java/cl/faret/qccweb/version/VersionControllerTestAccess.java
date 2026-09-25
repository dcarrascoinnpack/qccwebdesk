package cl.faret.qccweb.version;

/** Acceso de test al formato (package-private) del nombre de producto de /version. */
public final class VersionControllerTestAccess {

    private VersionControllerTestAccess() {}

    public static String producto(String gateway, String photinoVersion, String photinoCommit) {
        return VersionController.producto(gateway, photinoVersion, photinoCommit);
    }
}
