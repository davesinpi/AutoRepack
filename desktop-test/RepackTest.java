import com.android.apksig.ApkSigner;
import com.autorepack.core.ApkRewriter;
import com.autorepack.core.ManifestPatcher;

import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;

/**
 * Desktop check of the rewriting engine.
 * Usage: RepackTest keystore.p12 password newPkg uses(comma) base.apk [split.apk...]
 * Writes <apk>.repacked.apk next to each input.
 */
public class RepackTest {
    public static void main(String[] args) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(args[0])) {
            ks.load(in, args[1].toCharArray());
        }
        String alias = ks.aliases().nextElement();
        PrivateKey key = (PrivateKey) ks.getKey(alias, args[1].toCharArray());
        X509Certificate cert = (X509Certificate) ks.getCertificate(alias);

        String newPkg = args[2];
        String oldPkg = ApkRewriter.inspect(new File(args[4])).oldPackage;
        for (int i = 4; i < args.length; i++) {
            File in = new File(args[i]);
            File unsigned = new File(args[i] + ".unsigned.apk");
            File out = new File(args[i] + ".repacked.apk");
            ApkRewriter.Options o = new ApkRewriter.Options();
            o.oldPackage = oldPkg;
            o.newPackage = newPkg;
            o.base = i == 4;
            o.label = "Test (AA)";
            o.carUses = Arrays.asList(args[3].split(","));
            ManifestPatcher.Info info = ApkRewriter.rewrite(in, unsigned, o, null);
            System.out.println(in.getName() + ": " + info.oldPackage + " min=" + info.minSdk + " target=" + info.targetSdk);
            new ApkSigner.Builder(Collections.singletonList(
                    new ApkSigner.SignerConfig.Builder("AUTOREPK", key, Collections.singletonList(cert)).build()))
                    .setInputApk(unsigned).setOutputApk(out).build().sign();
            unsigned.delete();
            System.out.println("  -> " + out);
        }
    }
}
