package com.pyonphone.app;

import android.animation.ObjectAnimator;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class FileTreeAdapter extends RecyclerView.Adapter<FileTreeAdapter.ViewHolder> {

    public interface OnFileClickListener {
        void onFileClick(String path, boolean isDirectory);
    }

    public static class FileNode {
        public final String name;
        public final String path;
        public final boolean isDirectory;
        public final int level;
        public boolean expanded;
        public List<FileNode> children = new ArrayList<>();

        public FileNode(String name, String path, boolean isDirectory, int level) {
            this.name = name;
            this.path = path;
            this.isDirectory = isDirectory;
            this.level = level;
        }
    }

    private final List<FileNode> visibleNodes = new ArrayList<>();
    private FileNode root;
    private OnFileClickListener listener;
    private int lastAnimatedPosition = -1;

    public void setOnFileClickListener(OnFileClickListener listener) {
        this.listener = listener;
    }

    public void setTree(FileNode root) {
        this.root = root;
        lastAnimatedPosition = -1;
        rebuildVisibleList();
    }

    private void rebuildVisibleList() {
        visibleNodes.clear();
        if (root != null) {
            addChildren(root);
        }
        lastAnimatedPosition = -1;
        notifyDataSetChanged();
    }

    private void addChildren(FileNode node) {
        for (FileNode child : node.children) {
            visibleNodes.add(child);
            if (child.isDirectory && child.expanded) {
                addChildren(child);
            }
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file_tree, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FileNode node = visibleNodes.get(position);

        holder.name.setText(node.name);
        holder.name.setTypeface(null, node.isDirectory ? Typeface.BOLD : Typeface.NORMAL);

        int padding = (int) (node.level * 24 * holder.itemView.getResources().getDisplayMetrics().density);
        holder.itemView.setPadding(padding, holder.itemView.getPaddingTop(),
                holder.itemView.getPaddingRight(), holder.itemView.getPaddingBottom());

        holder.itemView.setOnClickListener(v -> {
            // Button press animation
            animateButtonClick(v);

            if (node.isDirectory) {
                node.expanded = !node.expanded;
                rebuildVisibleList();
            }
            if (listener != null) {
                listener.onFileClick(node.path, node.isDirectory);
            }
        });

        // Animate item appearance
        if (position > lastAnimatedPosition) {
            animateItem(holder.itemView, position);
            lastAnimatedPosition = position;
        }
    }

    private void animateItem(View view, int position) {
        view.setAlpha(0f);
        view.setTranslationX(-30f);

        view.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(300)
                .setStartDelay(position * 30L)
                .setInterpolator(new OvershootInterpolator(0.5f))
                .start();
    }

    private void animateButtonClick(View view) {
        ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.95f);
        ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.95f);
        scaleDownX.setDuration(80);
        scaleDownY.setDuration(80);

        ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(view, "scaleX", 0.95f, 1f);
        ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(view, "scaleY", 0.95f, 1f);
        scaleUpX.setDuration(150);
        scaleUpY.setDuration(150);
        scaleUpX.setInterpolator(new OvershootInterpolator(2f));
        scaleUpY.setInterpolator(new OvershootInterpolator(2f));

        scaleDownX.start();
        scaleDownY.start();
        scaleDownX.addUpdateListener(animation -> {
            if (animation.getAnimatedFraction() >= 1f) {
                scaleUpX.start();
                scaleUpY.start();
            }
        });
    }

    @Override
    public int getItemCount() {
        return visibleNodes.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;

        ViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.file_name);
        }
    }
}
