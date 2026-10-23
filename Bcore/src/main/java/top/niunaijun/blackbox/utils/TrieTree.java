package top.niunaijun.blackbox.utils;

import java.util.LinkedList;
import java.util.List;

public class TrieTree {

    //The root node of TrieTree
    private final TrieNode root = new TrieNode();

    //The node type of TrieTree
    private static class TrieNode {
        char content;
        String word;
        boolean isEnd = false; // This node is whether the end of a word
        List<TrieNode> children = new LinkedList<>();

        public TrieNode() {}

        public TrieNode(char content, String word) {
            this.content = content;
            this.word    = word;
        }


        @Override
        public boolean equals(Object object) {
            if (object instanceof TrieNode) {
                return ((TrieNode) object).content == content;
            }
            return false;
        }

        public TrieNode nextNode(char content) {
            for (TrieNode childNode : children) {
                if (childNode.content == content)
                    return childNode;
            }
            return null;
        }
    }

    public void add(String word) {
        TrieNode current = root;
        StringBuilder wordBuilder = new StringBuilder();
        for (int index = 0; index < word.length(); ++index) {
            char content = word.charAt(index);
            wordBuilder.append(content);
            TrieNode node = new TrieNode(content, wordBuilder.toString());
            if (current.children.contains(node)) {
                current = current.nextNode(content);
            } else {
                current.children.add(node);
                current = node;
            }

            if (index == (word.length() - 1))
                current.isEnd = true;
        }
    }

    public void addAll(List<String> words) {
        for (String word : words) {
            add(word);
        }
    }

    public String search(String word) {
        TrieNode current = root;
        String longest = null;
        for (int index = 0; index < word.length(); ++index) {
            char content = word.charAt(index);
            current = current.nextNode(content);
            if (current == null) break;
            if (current.isEnd && (index + 1 == word.length()
                    || content == '/' || word.charAt(index + 1) == '/')) {
                longest = current.word;
            }
        }
        return longest;
    }
}
