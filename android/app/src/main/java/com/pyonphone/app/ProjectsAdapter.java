package com.pyonphone.app;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class ProjectsAdapter extends RecyclerView.Adapter<ProjectsAdapter.ViewHolder> {

    public interface OnProjectClickListener {
        void onClick(Project project);
    }

    public interface OnProjectLongClickListener {
        void onLongClick(Project project);
    }

    private final List<Project> projects = new ArrayList<>();
    private OnProjectClickListener clickListener;
    private OnProjectLongClickListener longClickListener;
    private int lastAnimatedPosition = -1;

    public void setOnProjectClickListener(OnProjectClickListener listener) {
        this.clickListener = listener;
    }

    public void setOnProjectLongClickListener(OnProjectLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setProjects(List<Project> newProjects) {
        projects.clear();
        projects.addAll(newProjects);
        lastAnimatedPosition = -1;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_1, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Project project = projects.get(position);
        holder.text.setText(project.getId());
        holder.itemView.setOnClickListener(v -> {
            // Button press animation
            animateButtonClick(v);
            if (clickListener != null) clickListener.onClick(project);
        });
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) longClickListener.onLongClick(project);
            return true;
        });

        // Animate item appearance
        if (position > lastAnimatedPosition) {
            animateItem(holder.itemView, position);
            lastAnimatedPosition = position;
        }
    }

    private void animateItem(View view, int position) {
        view.setAlpha(0f);
        view.setTranslationY(50f);

        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(400)
                .setStartDelay(position * 50L)
                .setInterpolator(new OvershootInterpolator(0.8f))
                .start();
    }

    private void animateButtonClick(View view) {
        ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.92f);
        ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.92f);
        scaleDownX.setDuration(100);
        scaleDownY.setDuration(100);

        ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(view, "scaleX", 0.92f, 1f);
        ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(view, "scaleY", 0.92f, 1f);
        scaleUpX.setDuration(200);
        scaleUpY.setDuration(200);
        scaleUpX.setInterpolator(new OvershootInterpolator(2f));
        scaleUpY.setInterpolator(new OvershootInterpolator(2f));

        AnimatorSet scaleDown = new AnimatorSet();
        scaleDown.play(scaleDownX).with(scaleDownY);

        AnimatorSet scaleUp = new AnimatorSet();
        scaleUp.play(scaleUpX).with(scaleUpY);
        scaleUp.setStartDelay(100);

        AnimatorSet overall = new AnimatorSet();
        overall.play(scaleDown).before(scaleUp);
        overall.start();
    }

    @Override
    public int getItemCount() {
        return projects.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView text;

        ViewHolder(View itemView) {
            super(itemView);
            text = itemView.findViewById(android.R.id.text1);
        }
    }
}
