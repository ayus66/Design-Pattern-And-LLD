package com.learn.design.pattern;

interface Button {
    void render();
    void onClick(Runnable action);
}

interface TextField {
    void render();
    String getValue();
    void setValue(String text);
}

interface Checkbox {
    void render();
    boolean isChecked();
    void setChecked(boolean checked);
}

class MaterialButton implements Button {
    @Override
    public void render() { System.out.println("[Material] Rendering raised button with ripple effect"); }
    @Override
    public void onClick(Runnable action) { action.run(); }
}

class MaterialTextField implements TextField {
    private String value = "";
    @Override
    public void render() { System.out.println("[Material] Rendering outlined text field"); }
    @Override
    public String getValue() { return value; }
    @Override
    public void setValue(String text) { this.value = text; }
}

class MaterialCheckbox implements Checkbox {
    private boolean checked = false;
    @Override
    public void render() { System.out.println("[Material] Rendering checkbox with checkmark animation"); }
    @Override
    public boolean isChecked() { return checked; }
    @Override
    public void setChecked(boolean checked) { this.checked = checked; }
}

class AppleButton implements Button {
    @Override
    public void render() { System.out.println("[Apple] Rendering rounded button with haptic feedback"); }
    @Override
    public void onClick(Runnable action) { action.run(); }
}

class AppleTextField implements TextField {
    private String value = "";
    @Override
    public void render() { System.out.println("[Apple] Rendering bordered text field with clear button"); }
    @Override
    public String getValue() { return value; }
    @Override
    public void setValue(String text) { this.value = text; }
}

class AppleCheckbox implements Checkbox {
    private boolean checked = false;
    @Override
    public void render() { System.out.println("[Apple] Rendering toggle switch"); }
    @Override
    public boolean isChecked() { return checked; }
    @Override
    public void setChecked(boolean checked) { this.checked = checked; }
}

interface UIComponentFactory {
    Button createButton();
    TextField createTextField();
    Checkbox createCheckbox();
}

class MaterialUIFactory implements UIComponentFactory {

    @Override
    public Button createButton() { return new MaterialButton(); }

    @Override
    public TextField createTextField() { return new MaterialTextField(); }

    @Override
    public Checkbox createCheckbox() { return new MaterialCheckbox(); }
}

class AppleUIFactory implements UIComponentFactory {

    @Override
    public Button createButton() { return new AppleButton(); }

    @Override
    public TextField createTextField() { return new AppleTextField(); }

    @Override
    public Checkbox createCheckbox() { return new AppleCheckbox(); }
}

class LoginPage {

    private final Button loginButton;
    private final TextField usernameField;
    private final TextField passwordField;
    private final Checkbox rememberMeCheckbox;

    // Client depends on factory interface — doesn't know Material vs Apple
    public LoginPage(UIComponentFactory factory) {
        this.loginButton = factory.createButton();
        this.usernameField = factory.createTextField();
        this.passwordField = factory.createTextField();
        this.rememberMeCheckbox = factory.createCheckbox();
    }

    public void render() {
        System.out.println("=== Login Page ===");
        usernameField.render();
        passwordField.render();
        rememberMeCheckbox.render();
        loginButton.render();
    }
}
public class AbstractFactoryPattern {

    public static void main(String[] args) {
        String platform = System.getProperty("os.name").toLowerCase();
        UIComponentFactory factory = platform.contains("mac")
                ? new AppleUIFactory()
                : new MaterialUIFactory();

        LoginPage loginPage = new LoginPage(factory);
        loginPage.render();
    }
}
