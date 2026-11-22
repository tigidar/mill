package foo;

import com.google.gson.Gson;

public class Foo {
    public static void main(String[] args) {
        Gson gson = new Gson();
        String json = gson.toJson(new String[]{"hello", "maven-resolver"});
        System.out.println(json);
    }
}
