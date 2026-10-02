import android.content.Intent;
import android.os.IBinder;

public class DsvUnlock {
    public static void main(String[] args) throws Exception {
        Intent i = new Intent("com.desaysv.INSTALL_PACKAGES");
        i.setPackage("com.desaysv.engmode");
        i.putExtra("install_forbidden", 0);
        if (args != null && args.length > 0) {
            i.putExtra("whitelist_packages", args);
        }
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "package");
        Object pm = Class.forName("android.content.pm.IPackageManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, b);
        for (java.lang.reflect.Method m : pm.getClass().getMethods()) {
            if ("resolveService".equals(m.getName()) && m.getParameterCount() == 4) {
                Object r = m.invoke(pm, i, null, 0, 0);
                System.out.println("resolveService -> " + r);
                return;
            }
        }
        System.out.println("resolveService method not found");
    }
}
