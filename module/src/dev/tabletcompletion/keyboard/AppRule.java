package dev.tabletcompletion.keyboard;

/** Build-time app policy generated from rules/apps.json; contains no device state. */
public final class AppRule {
    public final String name;
    public final String defaultRatio;
    public final String packageName;
    public final String homeActivity;
    public final String searchActivity;
    public final boolean searchLaunchFallback;
    public final boolean manualKeyboard;
    public final boolean blockAds;

    public AppRule(String name, String defaultRatio, String packageName, String homeActivity, String searchActivity,
                   boolean searchLaunchFallback, boolean manualKeyboard, boolean blockAds) {
        this.name = name;
        this.defaultRatio = defaultRatio;
        this.packageName = packageName;
        this.homeActivity = homeActivity;
        this.searchActivity = searchActivity;
        this.searchLaunchFallback = searchLaunchFallback;
        this.manualKeyboard = manualKeyboard;
        this.blockAds = blockAds;
    }
}
