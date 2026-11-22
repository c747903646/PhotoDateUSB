import com.photodateusb.organizer.DateRules;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Runs with JDK 17, no Android SDK required. */
public class DateRulesTest {
    static int checks = 0;
    static void same(Object actual, Object expected) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected: " + expected + " actual: " + actual);
        }
    }
    static void yes(boolean value) {
        checks++;
        if (!value) throw new AssertionError("expected true");
    }
    public static void main(String[] args) {
        LocalDateTime exif = LocalDateTime.of(2021,5,18,14,30,25);
        same(DateRules.parseExif("2021:05:18 14:30:25"), exif);
        same(DateRules.parseExif("2021-05-18T14:30:25"), exif);
        same(DateRules.parseExif("2021:02:29 14:30:25"), null);
        same(DateRules.parseExif(null), null);
        same(DateRules.parseFilename("IMG_20210518_143025.jpg"), exif);
        same(DateRules.parseFilename("Screenshot_2021-05-18-14-30-25.png"), exif);
        same(DateRules.parseFilename("DSC_3042.JPG"), null);
        same(DateRules.parseFilename("PXL_20210518143025123.jpg"), exif);
        same(DateRules.folders(exif, DateRules.Group.YEAR), Arrays.asList("2021"));
        same(DateRules.folders(exif, DateRules.Group.MONTH), Arrays.asList("2021", "05"));
        same(DateRules.folders(exif, DateRules.Group.DAY), Arrays.asList("2021", "05", "18"));
        same(DateRules.folders(null, DateRules.Group.DAY), Arrays.asList("未识别日期"));
        same(DateRules.numberFile("IMG.JPG",1), "IMG_001.JPG");
        same(DateRules.numberFile("IMG",2), "IMG_002");
        same(DateRules.mimeFor("photo.HEIC"), "image/heic");
        yes(DateRules.imageFile("x.jpeg"));
        yes(DateRules.imageFile("x.HEIC"));
        yes(!DateRules.imageFile("movie.mp4"));
        yes(!DateRules.imageFile("readme.txt"));
        Set<String> unique = new HashSet<>();
        for(int i=0;i<500;i++) yes(unique.add(DateRules.numberFile("a.jpg",i)));
        System.out.println("PASS: " + checks + " pure-Java assertions");
    }
}
