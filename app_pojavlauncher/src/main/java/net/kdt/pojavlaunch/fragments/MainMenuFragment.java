package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.dialogOnUiThread;
import static net.kdt.pojavlaunch.Tools.hasMods;
import static net.kdt.pojavlaunch.Tools.hasNoOnlineProfileDialog;
import static net.kdt.pojavlaunch.Tools.hasOnlineProfile;
import static net.kdt.pojavlaunch.Tools.openPath;
import static net.kdt.pojavlaunch.Tools.runOnUiThread;
import static net.kdt.pojavlaunch.Tools.shareLog;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.modloaders.LWJGL3ifyUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.profiles.ProfileAdapter;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.List;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mNewsButton = view.findViewById(R.id.news_button);
        Button mDiscordButton = view.findViewById(R.id.discord_button);
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton = view.findViewById(R.id.install_jar_button);
        Button mModrinthButton = view.findViewById(R.id.modrinth_button);
        Button mShareLogsButton = view.findViewById(R.id.share_logs_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);

        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));
        mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), getString(R.string.discord_invite)));
        mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        mModrinthButton.setOnClickListener(v -> Tools.swapFragment(requireActivity(), SearchModFragment.class, SearchModFragment.TAG, null));
        if (hasOnlineProfile()) {
            mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
            mInstallJarButton.setOnLongClickListener(v -> {
                runInstallerWithConfirmation(true);
                return true;
            });
        } else mInstallJarButton.setOnClickListener(v -> hasNoOnlineProfileDialog(requireActivity()));
        mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));
        mVersionSpinner.setOnClickListener(v -> showInstancePicker());

        mPlayButton.setOnClickListener(v -> {
            if (Tools.hasMods("sodium") && !(LauncherPreferences.DEFAULT_PREF.getBoolean("sodium_override", false))) {
                AlertDialog sodiumWarningDialog = new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.sodium_warning_title)
                        .setMessage(R.string.sodium_warning_message)
                        .setNeutralButton(R.string.delete_sodium, (d,w)-> {
                            Tools.deleteSodiumMods();
                            ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
                        })
                        .create();
                sodiumWarningDialog.show();
            } else ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
        });

        mShareLogsButton.setOnClickListener((v) -> shareLog(requireContext()));

        mOpenDirectoryButton.setOnClickListener((v)-> {
            if (Tools.isDemoProfile(v.getContext())){ // Say a different message when on demo profile since they might see the hidden demo folder
                hasNoOnlineProfileDialog(getActivity(), getString(R.string.demo_unsupported), getString(R.string.change_account));
            } else if (!hasOnlineProfile()) { // Otherwise display the generic pop-up to log in
                hasNoOnlineProfileDialog(requireActivity());
            } else openPath(v.getContext(), getCurrentProfileDirectory(), false);

        });


        mNewsButton.setOnLongClickListener((v)->{
            Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
            return true;
        });
    }


    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundedBackground(int color, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(13));
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    /** Display installed profiles as large selectable game-library tiles. */
    private void showInstancePicker() {
        if (!isAdded()) return;
        mVersionSpinner.reloadProfiles();
        ProfileAdapter adapter = mVersionSpinner.getProfileAdapter();

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(12));
        root.setBackground(roundedBackground(Color.rgb(23, 24, 30), Color.rgb(58, 56, 69)));

        TextView heading = new TextView(requireContext());
        heading.setText("Your instances");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(22);
        heading.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        root.addView(heading);

        TextView description = new TextView(requireContext());
        description.setText("Choose a setup to launch");
        description.setTextColor(Color.rgb(160, 159, 171));
        description.setTextSize(12);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(4);
        descriptionParams.bottomMargin = dp(15);
        root.addView(description, descriptionParams);

        ScrollView scroll = new ScrollView(requireContext());
        LinearLayout cards = new LinearLayout(requireContext());
        cards.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(cards);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(root)
                .setNegativeButton("Close", null)
                .setNeutralButton("Manage", (d, which) -> mVersionSpinner.openProfileEditor(requireActivity()))
                .create();

        for (int i = 0; i < adapter.getCount(); i++) {
            Object item = adapter.getItem(i);
            if (!(item instanceof String)) continue;
            final int profileIndex = i;
            String profileId = (String) item;
            MinecraftProfile profile = LauncherProfiles.mainProfileJson.profiles.get(profileId);
            if (profile == null) continue;

            LinearLayout card = new LinearLayout(requireContext());
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(10), dp(10), dp(13), dp(10));
            card.setBackground(roundedBackground(Color.rgb(34, 35, 43), Color.rgb(55, 55, 67)));
            card.setClickable(true);
            card.setFocusable(true);

            ImageView artwork = new ImageView(requireContext());
            artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Drawable icon = ProfileIconCache.fetchIcon(getResources(), profileId, profile.icon);
            artwork.setImageDrawable(icon);
            artwork.setBackground(roundedBackground(Color.rgb(55, 43, 78), Color.rgb(111, 81, 171)));
            artwork.setClipToOutline(true);
            card.addView(artwork, new LinearLayout.LayoutParams(dp(108), dp(76)));

            LinearLayout labels = new LinearLayout(requireContext());
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(13), 0, dp(8), 0);
            labels.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

            TextView name = new TextView(requireContext());
            name.setText(Tools.isValidString(profile.name) ? profile.name : "Minecraft");
            name.setTextColor(Color.WHITE);
            name.setTextSize(15);
            name.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
            labels.addView(name);

            TextView version = new TextView(requireContext());
            version.setText((profile.lastVersionId == null ? "Minecraft" : profile.lastVersionId)
                    + (Tools.isValidString(profile.type) ? "  ·  " + profile.type : ""));
            version.setTextColor(Color.rgb(166, 164, 179));
            version.setTextSize(11);
            LinearLayout.LayoutParams versionParams = new LinearLayout.LayoutParams(-1, -2);
            versionParams.topMargin = dp(5);
            labels.addView(version, versionParams);

            TextView play = new TextView(requireContext());
            play.setText("▶");
            play.setTextColor(Color.rgb(178, 139, 255));
            play.setTextSize(23);
            play.setGravity(Gravity.CENTER);
            play.setBackground(roundedBackground(Color.rgb(48, 39, 67), Color.rgb(93, 69, 137)));
            card.addView(play, new LinearLayout.LayoutParams(dp(42), dp(42)));

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.bottomMargin = dp(9);
            cards.addView(card, cardParams);
            card.setOnClickListener(v -> {
                mVersionSpinner.setProfileSelection(profileIndex);
                dialog.dismiss();
            });
        }

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    Math.min(getResources().getDisplayMetrics().widthPixels - dp(28), dp(520)),
                    (int) (getResources().getDisplayMetrics().heightPixels * 0.82f));
        }
    }

    private File getCurrentProfileDirectory() {
        String currentProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if(!Tools.isValidString(currentProfile)) return new File(Tools.DIR_GAME_NEW);
        LauncherProfiles.load();
        MinecraftProfile profileObject = LauncherProfiles.mainProfileJson.profiles.get(currentProfile);
        if(profileObject == null) return new File(Tools.DIR_GAME_NEW);
        return Tools.getGameDirPath(profileObject);
    }

    @Override
    public void onResume() {
        super.onResume();
        mVersionSpinner.reloadProfiles();
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        if (ProgressKeeper.getTaskCount() == 0)
            Tools.installMod(requireActivity(), isCustomArgs);
        else
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }
}
