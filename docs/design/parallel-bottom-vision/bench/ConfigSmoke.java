import java.io.File;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Collection;
import org.openpnp.model.Configuration;
import org.simpleframework.xml.Serializer;

/**
 * Read the machine's own configuration with the same XStream setup OpenPnP uses, and write it out again to
 * another directory. Nothing is initialised: no machine, no cameras, no ports. This is the layer that a new
 * JVM breaks first, because simple-xml reaches into fields by reflection, and it is the layer that has to be
 * right before a machine is trusted with a new JVM.
 *
 * argv: configuration directory, output directory
 */
public class ConfigSmoke {
    public static void main(String[] argv) throws Exception {
        File config = new File(argv.length > 0 ? argv[0] : System.getProperty("user.home") + "/.openpnp2");
        File out = new File(argv.length > 1 ? argv[1] : "/tmp/config-smoke");
        out.mkdirs();
        System.out.println("java=" + System.getProperty("java.version") + " config=" + config);
        read(config, out, "machine.xml", Configuration.MachineConfigurationHolder.class);
        read(config, out, "vision-settings.xml", Configuration.VisionSettingsConfigurationHolder.class);
        read(config, out, "packages.xml", Configuration.PackagesConfigurationHolder.class);
        read(config, out, "parts.xml", Configuration.PartsConfigurationHolder.class);
    }

    static void read(File config, File out, String name, Class<?> holder) {
        File file = new File(config, name);
        if(!file.exists()) {
            System.out.println("  " + name + ": absent, skipped");
            return;
        }
        try {
            Serializer serializer = createSerializer();
            Object read = serializer.read(holder, file);
            StringWriter sw = new StringWriter();
            serializer.write(read, sw);
            String xml = sw.toString();
            java.nio.file.Files.write(new File(out, name).toPath(), xml.getBytes("UTF-8"));
            System.out.println("  " + name + ": read " + count(read) + ", wrote " + xml.length()
                    + " chars, same length as source: " + (xml.length() == file.length()));
        }
        catch(Exception e) {
            Throwable t = e;
            while(t.getCause() != null) {
                t = t.getCause();
            }
            System.out.println("  " + name + ": FAILED " + e.getClass().getSimpleName() + " " + e.getMessage()
                    + " / root " + t.getClass().getName() + " " + t.getMessage());
        }
    }

    static String count(Object holder) {
        for(Field field : holder.getClass().getDeclaredFields()) {
            if(Collection.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                try {
                    Collection<?> c = (Collection<?>) field.get(holder);
                    return c == null ? "empty" : c.size() + " entries";
                }
                catch(Exception e) {
                    return "count failed: " + e;
                }
            }
        }
        return "single object";
    }

    static Serializer createSerializer() {
        return Configuration.createSerializer();
    }
}
