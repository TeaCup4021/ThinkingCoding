/**
 * HelloXUPT - 西安邮电大学欢迎程序
 * 
 * 打印西安邮电大学的欢迎信息
 */
public class HelloXUPT {

    private String universityName;
    private String motto;

    public HelloXUPT() {
        this.universityName = "西安邮电大学";
        this.motto = "爱国、求是、奋进";
    }

    public HelloXUPT(String universityName, String motto) {
        this.universityName = universityName;
        this.motto = motto;
    }

    public String getUniversityName() {
        return universityName;
    }

    public String getMotto() {
        return motto;
    }

    public String getGreeting() {
        return "你好，" + universityName + "！";
    }

    public String getFullIntroduction() {
        return "欢迎来到 " + universityName + "！\n校训：" + motto;
    }

    public void printGreeting() {
        System.out.println(getGreeting());
    }

    public void printFullIntroduction() {
        System.out.println(getFullIntroduction());
    }

    public static void main(String[] args) {
        HelloXUPT hello = new HelloXUPT();
        hello.printFullIntroduction();
    }
}
