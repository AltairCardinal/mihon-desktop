package ec.future;

public class LinkageProbe {
    public static void missingMethod() { org.jsoup.Jsoup.ecFutureCompatibility(); }
    public static void missingClass() { ec.absent.FutureApi.required(); }
}
