#!/bin/bash
echo "Running GitHub Sync..."

# Move to the root directory
cd "$(dirname "$0")/.."

# Load from .env if variables are empty
if [ -z "$GITHUB_PAT" ] || [ -z "$GITHUB_REPO" ]; then
  if [ -f .env ]; then
    export $(grep -E '^(GITHUB_PAT|GITHUB_REPO)=' .env | sed 's/"//g' | xargs)
  fi
fi

if [ -z "$GITHUB_PAT" ] || [ -z "$GITHUB_REPO" ]; then
  echo "GITHUB_PAT or GITHUB_REPO is missing. Skipping sync."
  exit 0
fi

if ! command -v git &> /dev/null; then
    echo "git could not be found, skipping sync"
    exit 0
fi

# Set up git
git config --global user.email "bot@aistudio.com"
git config --global user.name "AI Studio Bot"
git config --global pull.rebase false

# Init git if necessary
if [ ! -d ".git" ]; then
  git init
fi

# Clean up repo name in case user pasted a URL
REPO=${GITHUB_REPO#https://github.com/}
REPO=${REPO%.git}

git remote remove origin 2>/dev/null
git remote add origin "https://${GITHUB_PAT}@github.com/${REPO}.git"

# Fetch main
git fetch origin main 2>/dev/null

# Attempt an initial local commit so merge works
git add -A
git commit -m "Auto sync from AI Studio Build" || echo "No local changes to commit"
git branch -M main

# Merge remote changes if they exist (to not overwrite previous state without history)
if git ls-remote --exit-code --heads origin main 2>/dev/null; then
    git merge origin/main --allow-unrelated-histories -m "Merge remote upstream" 2>/dev/null || echo "Merge encountered issues"
fi

# Add and commit any merge resolutions
git add -A
git commit -m "Merge resolution from AI Studio Build" || true

# Force push to prevent failures if there were untracked conflicts
git push -u origin main \
 || git push origin main --force 

echo "GitHub Sync Complete!"
