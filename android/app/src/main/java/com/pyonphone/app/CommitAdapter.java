package com.pyonphone.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class CommitAdapter extends RecyclerView.Adapter<CommitAdapter.ViewHolder> {

    public static class Commit {
        public final String hash;
        public final String message;
        public final String date;

        public Commit(String hash, String message, String date) {
            this.hash = hash;
            this.message = message;
            this.date = date;
        }
    }

    private final List<Commit> commits = new ArrayList<>();
    private int lastAnimatedPosition = -1;

    public void setCommits(List<Commit> newCommits) {
        commits.clear();
        commits.addAll(newCommits);
        lastAnimatedPosition = -1;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_commit, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Commit commit = commits.get(position);
        holder.message.setText(commit.message);
        holder.hash.setText(commit.hash.substring(0, Math.min(7, commit.hash.length())));
        holder.date.setText(commit.date);

        // Animate item appearance
        if (position > lastAnimatedPosition) {
            animateItem(holder.itemView, position);
            lastAnimatedPosition = position;
        }
    }

    private void animateItem(View view, int position) {
        view.setAlpha(0f);
        view.setTranslationY(30f);

        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(350)
                .setStartDelay(position * 40L)
                .setInterpolator(new OvershootInterpolator(0.6f))
                .start();
    }

    @Override
    public int getItemCount() {
        return commits.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView message, hash, date;

        ViewHolder(View itemView) {
            super(itemView);
            message = itemView.findViewById(R.id.commit_message);
            hash = itemView.findViewById(R.id.commit_hash);
            date = itemView.findViewById(R.id.commit_date);
        }
    }
}
