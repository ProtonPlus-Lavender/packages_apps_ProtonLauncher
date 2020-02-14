/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.quickstep;

import static android.view.View.TRANSLATION_Y;

import static com.android.launcher3.LauncherAnimUtils.SCALE_PROPERTY;
import static com.android.launcher3.LauncherAppTransitionManagerImpl.INDEX_RECENTS_FADE_ANIM;
import static com.android.launcher3.LauncherAppTransitionManagerImpl.INDEX_RECENTS_TRANSLATE_X_ANIM;
import static com.android.launcher3.LauncherAppTransitionManagerImpl.INDEX_SHELF_ANIM;
import static com.android.launcher3.LauncherState.BACKGROUND_APP;
import static com.android.launcher3.LauncherState.NORMAL;
import static com.android.launcher3.LauncherState.OVERVIEW;
import static com.android.launcher3.anim.Interpolators.ACCEL_2;
import static com.android.launcher3.anim.Interpolators.ACCEL_DEACCEL;
import static com.android.launcher3.anim.Interpolators.INSTANT;
import static com.android.launcher3.anim.Interpolators.LINEAR;
import static com.android.quickstep.LauncherSwipeHandler.RECENTS_ATTACH_DURATION;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.UserHandle;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import com.android.launcher3.BaseQuickstepLauncher;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherInitListener;
import com.android.launcher3.LauncherState;
import com.android.launcher3.allapps.DiscoveryBounce;
import com.android.launcher3.anim.AnimatorPlaybackController;
import com.android.launcher3.appprediction.PredictionUiStateManager;
import com.android.launcher3.touch.PortraitPagedViewHandler;
import com.android.launcher3.touch.PagedOrientationHandler;
import com.android.launcher3.userevent.nano.LauncherLogProto;
import com.android.launcher3.views.FloatingIconView;
import com.android.quickstep.SysUINavigationMode.Mode;
import com.android.quickstep.util.ActivityInitListener;
import com.android.quickstep.util.LayoutUtils;
import com.android.quickstep.util.ShelfPeekAnim;
import com.android.quickstep.util.ShelfPeekAnim.ShelfAnimState;
import com.android.quickstep.util.StaggeredWorkspaceAnim;
import com.android.quickstep.views.LauncherRecentsView;
import com.android.quickstep.views.RecentsView;
import com.android.quickstep.views.TaskView;
import com.android.systemui.plugins.shared.LauncherOverlayManager;
import com.android.systemui.shared.recents.model.ThumbnailData;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * {@link BaseActivityInterface} for the in-launcher recents.
 */
public final class LauncherActivityInterface implements BaseActivityInterface<Launcher> {

    private Runnable mAdjustInterpolatorsRunnable;

    @Override
    public int getSwipeUpDestinationAndLength(DeviceProfile dp, Context context, Rect outRect) {
        LayoutUtils.calculateLauncherTaskSize(context, dp, outRect);
        if (dp.isVerticalBarLayout() && SysUINavigationMode.getMode(context) != Mode.NO_BUTTON) {
            Rect targetInsets = dp.getInsets();
            int hotseatInset = dp.isSeascape() ? targetInsets.left : targetInsets.right;
            return dp.hotseatBarSizePx + hotseatInset;
        } else {
            return LayoutUtils.getShelfTrackingDistance(context, dp);
        }
    }

    @Override
    public void onTransitionCancelled(boolean activityVisible) {
        Launcher launcher = getCreatedActivity();
        if (launcher == null) {
            return;
        }
        LauncherState startState = launcher.getStateManager().getRestState();
        launcher.getStateManager().goToState(startState, activityVisible);
    }

    @Override
    public void onSwipeUpToRecentsComplete() {
        // Re apply state in case we did something funky during the transition.
        Launcher launcher = getCreatedActivity();
        if (launcher == null) {
            return;
        }
        launcher.getStateManager().reapplyState();
        DiscoveryBounce.showForOverviewIfNeeded(launcher);
    }

    @Override
    public void onSwipeUpToHomeComplete() {
        Launcher launcher = getCreatedActivity();
        if (launcher == null) {
            return;
        }
        // Ensure recents is at the correct position for NORMAL state. For example, when we detach
        // recents, we assume the first task is invisible, making translation off by one task.
        launcher.getStateManager().reapplyState();
        setLauncherHideBackArrow(false);
    }

    private void setLauncherHideBackArrow(boolean hideBackArrow) {
        Launcher launcher = getCreatedActivity();
        if (launcher == null) {
            return;
        }
        launcher.getRootView().setForceHideBackArrow(hideBackArrow);
    }

    @Override
    public void onAssistantVisibilityChanged(float visibility) {
        Launcher launcher = getCreatedActivity();
        if (launcher == null) {
            return;
        }
        launcher.onAssistantVisibilityChanged(visibility);
    }

    @NonNull
    @Override
    public HomeAnimationFactory prepareHomeUI() {
        Launcher launcher = getCreatedActivity();
        final DeviceProfile dp = launcher.getDeviceProfile();
        final RecentsView recentsView = launcher.getOverviewPanel();
        final TaskView runningTaskView = recentsView.getRunningTaskView();
        final View workspaceView;
        if (runningTaskView != null && runningTaskView.getTask().key.getComponent() != null) {
            workspaceView = launcher.getWorkspace().getFirstMatchForAppClose(
                    runningTaskView.getTask().key.getComponent().getPackageName(),
                    UserHandle.of(runningTaskView.getTask().key.userId));
        } else {
            workspaceView = null;
        }
        final RectF iconLocation = new RectF();
        boolean canUseWorkspaceView = workspaceView != null && workspaceView.isAttachedToWindow();
        FloatingIconView floatingIconView = canUseWorkspaceView
                ? FloatingIconView.getFloatingIconView(launcher, workspaceView,
                        true /* hideOriginal */, iconLocation, false /* isOpening */)
                : null;
        setLauncherHideBackArrow(true);
        return new HomeAnimationFactory() {
            @Nullable
            @Override
            public View getFloatingView() {
                return floatingIconView;
            }

            @NonNull
            @Override
            public RectF getWindowTargetRect() {
                if (canUseWorkspaceView) {
                    return iconLocation;
                } else {
                    return HomeAnimationFactory
                        .getDefaultWindowTargetRect(recentsView.getPagedOrientationHandler(), dp);
                }
            }

            @NonNull
            @Override
            public AnimatorPlaybackController createActivityAnimationToHome() {
                // Return an empty APC here since we have an non-user controlled animation to home.
                long accuracy = 2 * Math.max(dp.widthPx, dp.heightPx);
                return launcher.getStateManager().createAnimationToNewWorkspace(NORMAL, accuracy,
                        0 /* animComponents */);
            }

            @Override
            public void playAtomicAnimation(float velocity) {
                new StaggeredWorkspaceAnim(launcher, velocity, true /* animateOverviewScrim */)
                        .start();
            }
        };
    }

    @Override
    public AnimationFactory prepareRecentsUI(boolean activityVisible,
            boolean animateActivity, Consumer<AnimatorPlaybackController> callback) {
        BaseQuickstepLauncher launcher = getCreatedActivity();
        final LauncherState startState = launcher.getStateManager().getState();

        LauncherState resetState = startState;
        if (startState.disableRestore) {
            resetState = launcher.getStateManager().getRestState();
        }
        launcher.getStateManager().setRestState(resetState);

        final LauncherState fromState = animateActivity ? BACKGROUND_APP : OVERVIEW;
        launcher.getStateManager().goToState(fromState, false);
        // Since all apps is not visible, we can safely reset the scroll position.
        // This ensures then the next swipe up to all-apps starts from scroll 0.
        launcher.getAppsView().reset(false /* animate */);

        return new AnimationFactory() {
            private final ShelfPeekAnim mShelfAnim = launcher.getShelfPeekAnim();
            private boolean mIsAttachedToWindow;

            @Override
            public void createActivityInterface(long transitionLength) {
                createActivityInterfaceInternal(launcher, fromState, transitionLength, callback);
                // Creating the activity controller animation sometimes reapplies the launcher state
                // (because we set the animation as the current state animation), so we reapply the
                // attached state here as well to ensure recents is shown/hidden appropriately.
                if (SysUINavigationMode.getMode(launcher) == Mode.NO_BUTTON) {
                    setRecentsAttachedToAppWindow(mIsAttachedToWindow, false);
                }
            }

            @Override
            public void adjustActivityControllerInterpolators() {
                if (mAdjustInterpolatorsRunnable != null) {
                    mAdjustInterpolatorsRunnable.run();
                    mAdjustInterpolatorsRunnable = null;
                }
            }

            @Override
            public void onTransitionCancelled() {
                launcher.getStateManager().goToState(startState, false /* animate */);
            }

            @Override
            public void setShelfState(ShelfAnimState shelfState, Interpolator interpolator,
                    long duration) {
                mShelfAnim.setShelfState(shelfState, interpolator, duration);
            }

            @Override
            public void setRecentsAttachedToAppWindow(boolean attached, boolean animate) {
                if (mIsAttachedToWindow == attached && animate) {
                    return;
                }
                mIsAttachedToWindow = attached;
                LauncherRecentsView recentsView = launcher.getOverviewPanel();
                Animator fadeAnim = launcher.getStateManager()
                        .createStateElementAnimation(
                        INDEX_RECENTS_FADE_ANIM, attached ? 1 : 0);

                int runningTaskIndex = recentsView.getRunningTaskIndex();
                if (runningTaskIndex == recentsView.getTaskViewStartIndex()) {
                    // If we are on the first task (we haven't quick switched), translate recents in
                    // from the side. Calculate the start translation based on current scale/scroll.
                    float currScale = recentsView.getScaleX();
                    float scrollOffsetX = recentsView.getScrollOffset();
                    float offscreenX = recentsView.getOffscreenTranslationX(currScale);

                    float fromTranslation = attached ? offscreenX - scrollOffsetX : 0;
                    float toTranslation = attached ? 0 : offscreenX - scrollOffsetX;
                    launcher.getStateManager()
                            .cancelStateElementAnimation(INDEX_RECENTS_TRANSLATE_X_ANIM);

                    PagedOrientationHandler pagedOrientationHandler =
                        recentsView.getPagedViewOrientedState().getOrientationHandler();
                    if (!recentsView.isShown() && animate) {
                        pagedOrientationHandler
                            .getPrimaryViewTranslate().set(recentsView, fromTranslation);
                    } else {
                        fromTranslation =
                            pagedOrientationHandler.getPrimaryViewTranslate().get(recentsView);
                    }

                    if (!animate) {
                        pagedOrientationHandler
                            .getPrimaryViewTranslate().set(recentsView, toTranslation);
                    } else {
                        launcher.getStateManager().createStateElementAnimation(
                                INDEX_RECENTS_TRANSLATE_X_ANIM,
                                fromTranslation, toTranslation).start();
                    }

                    fadeAnim.setInterpolator(attached ? INSTANT : ACCEL_2);
                } else {
                    fadeAnim.setInterpolator(ACCEL_DEACCEL);
                }
                fadeAnim.setDuration(animate ? RECENTS_ATTACH_DURATION : 0).start();
            }
        };
    }

    private void createActivityInterfaceInternal(Launcher activity, LauncherState fromState,
            long transitionLength, Consumer<AnimatorPlaybackController> callback) {
        LauncherState endState = OVERVIEW;
        if (fromState == endState) {
            return;
        }

        AnimatorSet anim = new AnimatorSet();
        if (!activity.getDeviceProfile().isVerticalBarLayout()
                && SysUINavigationMode.getMode(activity) != Mode.NO_BUTTON) {
            // Don't animate the shelf when the mode is NO_BUTTON, because we update it atomically.
            anim.play(activity.getStateManager().createStateElementAnimation(
                    INDEX_SHELF_ANIM,
                    fromState.getVerticalProgress(activity),
                    endState.getVerticalProgress(activity)));
        }
        playScaleDownAnim(anim, activity, fromState, endState);

        anim.setDuration(transitionLength * 2);
        anim.setInterpolator(LINEAR);
        AnimatorPlaybackController controller =
                AnimatorPlaybackController.wrap(anim, transitionLength * 2);
        activity.getStateManager().setCurrentUserControlledAnimation(controller);

        // Since we are changing the start position of the UI, reapply the state, at the end
        controller.setEndAction(() -> {
            activity.getStateManager().goToState(
                    controller.getInterpolatedProgress() > 0.5 ? endState : fromState, false);
        });
        callback.accept(controller);
    }

    /**
     * Scale down recents from the center task being full screen to being in overview.
     */
    private void playScaleDownAnim(AnimatorSet anim, Launcher launcher, LauncherState fromState,
            LauncherState endState) {
        RecentsView recentsView = launcher.getOverviewPanel();
        if (recentsView.getCurrentPageTaskView() == null) {
            return;
        }

        LauncherState.ScaleAndTranslation fromScaleAndTranslation
                = fromState.getOverviewScaleAndTranslation(launcher);
        LauncherState.ScaleAndTranslation endScaleAndTranslation
                = endState.getOverviewScaleAndTranslation(launcher);
        float fromTranslationY = fromScaleAndTranslation.translationY;
        float endTranslationY = endScaleAndTranslation.translationY;
        float fromFullscreenProgress = fromState.getOverviewFullscreenProgress();
        float endFullscreenProgress = endState.getOverviewFullscreenProgress();

        Animator scale = ObjectAnimator.ofFloat(recentsView, SCALE_PROPERTY,
                fromScaleAndTranslation.scale, endScaleAndTranslation.scale);
        Animator translateY = ObjectAnimator.ofFloat(recentsView, TRANSLATION_Y,
                fromTranslationY, endTranslationY);
        Animator applyFullscreenProgress = ObjectAnimator.ofFloat(recentsView,
                RecentsView.FULLSCREEN_PROGRESS, fromFullscreenProgress, endFullscreenProgress);
        anim.playTogether(scale, translateY, applyFullscreenProgress);

        mAdjustInterpolatorsRunnable = () -> {
            // Adjust the translateY interpolator to account for the running task's top inset.
            // When progress <= 1, this is handled by each task view as they set their fullscreen
            // progress. However, once we go to progress > 1, fullscreen progress stays at 0, so
            // recents as a whole needs to translate further to keep up with the app window.
            TaskView runningTaskView = recentsView.getRunningTaskView();
            if (runningTaskView == null) {
                runningTaskView = recentsView.getCurrentPageTaskView();
                if (runningTaskView == null) {
                    // There are no task views in LockTask mode when Overview is enabled.
                    return;
                }
            }
            TimeInterpolator oldInterpolator = translateY.getInterpolator();
            Rect fallbackInsets = launcher.getDeviceProfile().getInsets();
            float extraTranslationY = runningTaskView.getThumbnail().getInsets(fallbackInsets).top;
            float normalizedTranslationY = extraTranslationY / (fromTranslationY - endTranslationY);
            translateY.setInterpolator(t -> {
                float newT = oldInterpolator.getInterpolation(t);
                return newT <= 1f ? newT : newT + normalizedTranslationY * (newT - 1);
            });
        };
    }

    @Override
    public ActivityInitListener createActivityInitListener(Predicate<Boolean> onInitListener) {
        return new LauncherInitListener((activity, alreadyOnHome) ->
                onInitListener.test(alreadyOnHome));
    }

    @Nullable
    @Override
    public BaseQuickstepLauncher getCreatedActivity() {
        return BaseQuickstepLauncher.ACTIVITY_TRACKER.getCreatedActivity();
    }

    @Nullable
    @UiThread
    private Launcher getVisibleLauncher() {
        Launcher launcher = getCreatedActivity();
        return (launcher != null) && launcher.isStarted() && launcher.hasWindowFocus() ?
                launcher : null;
    }

    @Nullable
    @Override
    public RecentsView getVisibleRecentsView() {
        Launcher launcher = getVisibleLauncher();
        return launcher != null && launcher.getStateManager().getState().overviewUi
                ? launcher.getOverviewPanel() : null;
    }

    @Override
    public boolean switchToRecentsIfVisible(Runnable onCompleteCallback) {
        Launcher launcher = getVisibleLauncher();
        if (launcher == null) {
            return false;
        }

        launcher.getUserEventDispatcher().logActionCommand(
                LauncherLogProto.Action.Command.RECENTS_BUTTON,
                getContainerType(),
                LauncherLogProto.ContainerType.TASKSWITCHER);
        launcher.getStateManager().goToState(OVERVIEW,
                launcher.getStateManager().shouldAnimateStateChange(), onCompleteCallback);
        return true;
    }

    @Override
    public boolean deferStartingActivity(RecentsAnimationDeviceState deviceState, MotionEvent ev) {
        return deviceState.isInDeferredGestureRegion(ev);
    }

    @Override
    public Rect getOverviewWindowBounds(Rect homeBounds, RemoteAnimationTargetCompat target) {
        return homeBounds;
    }

    @Override
    public boolean shouldMinimizeSplitScreen() {
        return true;
    }

    @Override
    public int getContainerType() {
        final Launcher launcher = getVisibleLauncher();
        return launcher != null ? launcher.getStateManager().getState().containerType
                : LauncherLogProto.ContainerType.APP;
    }

    @Override
    public boolean isInLiveTileMode() {
        Launcher launcher = getCreatedActivity();
        return launcher != null && launcher.getStateManager().getState() == OVERVIEW &&
                launcher.isStarted();
    }

    @Override
    public void onLaunchTaskFailed() {
        Launcher launcher = getCreatedActivity();
        if (launcher == null) {
            return;
        }
        launcher.getStateManager().goToState(OVERVIEW);
    }

    @Override
    public void onLaunchTaskSuccess() {
        Launcher launcher = getCreatedActivity();
        if (launcher == null) {
            return;
        }
        launcher.getStateManager().moveToRestState();
    }

    @Override
    public void closeOverlay() {
        Launcher launcher = getCreatedActivity();
        if (launcher == null) {
            return;
        }
        LauncherOverlayManager om = launcher.getOverlayManager();
        if (!launcher.isStarted() || launcher.isForceInvisible()) {
            om.hideOverlay(false /* animate */);
        } else {
            om.hideOverlay(150);
        }
    }

    @Override
    public void switchRunningTaskViewToScreenshot(ThumbnailData thumbnailData,
            Runnable onFinishRunnable) {
        Launcher launcher = getCreatedActivity();
        if (launcher == null) {
            return;
        }
        RecentsView recentsView = launcher.getOverviewPanel();
        if (recentsView == null) {
            if (onFinishRunnable != null) {
                onFinishRunnable.run();
            }
            return;
        }
        recentsView.switchToScreenshot(thumbnailData, onFinishRunnable);
    }

    @Override
    public void setOnDeferredActivityLaunchCallback(Runnable r) {
        Launcher launcher = getCreatedActivity();
        if (launcher == null) {
            return;
        }
        launcher.setOnDeferredActivityLaunchCallback(r);
    }

    @Override
    public void updateOverviewPredictionState() {
        Launcher launcher = getCreatedActivity();
        if (launcher == null) {
            return;
        }
        PredictionUiStateManager.INSTANCE.get(launcher).switchClient(
                PredictionUiStateManager.Client.OVERVIEW);
    }
}