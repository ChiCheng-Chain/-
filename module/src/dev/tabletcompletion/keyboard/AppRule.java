package dev.tabletcompletion.keyboard;

/** Build-time app policy generated from rules/apps.json; contains no device state. */
public final class AppRule {
    public final String packageName;
    public final String homeActivity;
    public final String searchActivity;
    public final boolean searchLaunchFallback;
    public final boolean manualKeyboard;

    public AppRule(String packageName, String homeActivity, String searchActivity,
                   boolean searchLaunchFallback, boolean manualKeyboard) {
        this.packageName = packageName;
        this.homeActivity = homeActivity;
        this.searchActivity = searchActivity;
        this.searchLaunchFallback = searchLaunchFallback;
        this.manualKeyboard = manualKeyboard;
    }
}
