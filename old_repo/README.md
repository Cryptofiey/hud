# Poker HUD Task File Explanation

This folder contains the key files for the Poker Equity HUD application that were requested for transfer to another environment.

## Files Included:

### 1. Core Screen Capture Components:
- **OverlayService.java** - Handles MediaProjection initialization and screen capture setup
  - Initializes virtual display for continuous screen capture
  - Manages screen capture lifecycle and cleanup
  - Sets up overlay UI components for HUD display
  - Handles orientation changes and display metrics

### 2. Image Processing & Card Recognition:
- **Recognition.java** - Processes detected card information
  - Converts raw pixel data to card recognition results
  - Tracks confidence scores and temporal filtering
  - Provides spatial data (location, margins, zoom levels)
  - Converts recognition to Card objects for poker logic

- **PixelDetector.java** - Low-level image processing pipeline
  - Uses RenderScript for GPU-accelerated image processing
  - Performs template matching against card templates
  - Handles background subtraction and multi-card detection
  - Returns match statistics for card regions

### 3. Advisor Engine & Calculation Components:
- **AdvisorEngine.java** - Intelligent recommendation system
  - Combines multiple factors: equity, Sklansky stats, player stats, position, stage, dynamics
  - Implements adaptive weighting based on available data
  - Provides explainable recommendations with confidence scores
  - Maps analysis to poker actions (FOLD/CHECK/CALL/RAISE/ALL_IN)

- **HoldemCalculator.java** - Texas Hold'em equity calculations
  - Calculates win/draw/loss rates vs opponents
  - Part of the poker calculator suite used by AdvisorEngine
  - Other calculators (Omaha, SixPlus, etc.) follow similar patterns

### 4. Supporting Documentation:
- **poker_hud_documentation.txt** - Comprehensive explanation of:
  - Screen capture and image processing flow
  - Advisor engine decision-making process
  - Data flow from screen capture to HUD display
  - Configuration and persistence mechanisms
  - How checkboxes control feature activation

### 5. Configuration Files (previously created):
- **poker_checkbox_settings.json** - JSON configuration for advisor checkboxes
- **poker_checkbox_and_screen.json** - JSON configuration for screen areas and advisor parameters

## How the System Works Together:

1. **Screen Capture Initiation:**
   - OverlayService requests and receives MediaProjection from system
   - startScreenCapture() initializes virtual display for continuous frame capture
   - Frames are processed by ChangeFinder (uses RenderScript) in real-time

2. **Card Detection Pipeline:**
   - Captured frames processed by PixelDetector.java
   - Template matching identifies potential card regions
   - Recognition.java converts detections to structured card data
   - Spatial information (location, confidence) is maintained

3. **Decision Making Process:**
   - Recognized cards fed to poker calculators (HoldemCalculator, etc.)
   - Calculators provide equity/win-rate data vs opponents
   - Historical player data tracked in PlayerDatabase (VPIP, PFR, aggression)
   - AdvisorEngine combines all factors with adaptive weighting
   - Generates action recommendation with confidence and explanation

4. **HUD Display & User Control:**
   - Recommendations displayed via AdvisorChart in overlay
   - Display controlled by checkboxes in Settings:
     * advisorMainEnabled - Shows main HUD (pot, PIP, blinds, bet)
     * advisorPlayerStatsEnabled - Shows extended stats on avatar click
   - Checkbox states persisted via SharedPreferences
   - Screen areas (handArea/boardArea) also persist for consistent detection

5. **Data Persistence:**
   - All user settings stored in SharedPreferences via Settings.java
   - Screen capture regions persist for consistent detection areas
   - Advisor parameters updated from historical screen data
   - Checkbox states maintained across app restarts

## Key Integration Points:

- **Checkbox to Feature Mapping:**
  * advisorMainEnabled ↔ VisualizationFragment shows advisor HUD elements
  * advisorPlayerStatsEnabled ↔ VisualizationFragment shows player stats HUD

- **Screen Area Usage:**
  * handArea/settings → OverlayService loadPositions() → Recognition detection zones
  * boardArea/settings → Same flow for community cards

- **Parameter Flow:**
  * Screen detection → PlayerDatabase updates → AdvisorEngine reads advisorVPIP/PFR/etc.
  * These parameters dynamically calculated from observed player actions

This collection provides everything needed to understand and potentially recreate the screen capture, image processing, advisor decision-making, and HUD display systems in another environment.