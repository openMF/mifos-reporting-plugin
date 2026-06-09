package org.apache.fineract.infrastructure.report.service;

import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.pentaho.reporting.engine.classic.core.ClassicEngineBoot;
import org.pentaho.reporting.engine.classic.core.modules.output.support.itext.BaseFontModule;
import org.pentaho.reporting.libraries.fonts.itext.ITextFontRegistry;
import org.pentaho.reporting.libraries.fonts.truetype.TrueTypeFontRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class to register custom TTF fonts with the Pentaho Reporting Engine.
 *
 * <p>
 * This registrar ensures that custom TrueType fonts are available across all
 * Pentaho output targets:
 * <ul>
 *   <li><b>PDF</b> — via {@link ITextFontRegistry} (iText-based)</li>
 *   <li><b>HTML/XLS/XLSX</b> — via AWT {@link GraphicsEnvironment}</li>
 *   <li><b>General metrics</b> — via {@link TrueTypeFontRegistry}</li>
 * </ul>
 *
 * <p>
 * Fonts should be placed in a directory specified by the system property
 * {@code fineract.pentaho.custom.fonts.dir} or the environment variable
 * {@code FINERACT_PENTAHO_CUSTOM_FONTS_DIR}.
 *
 * <p>
 * This class must be invoked <b>after</b> {@link ClassicEngineBoot#start()} has
 * been called, since the font registries depend on the engine being initialized.
 *
 * @author Mifos Pentaho Plugin Team
 */
public final class CustomFontRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(CustomFontRegistrar.class);

    /** System property / env variable name for the custom fonts directory. */
    public static final String FONTS_DIR_PROPERTY = "fineract.pentaho.custom.fonts.dir";
    public static final String FONTS_DIR_ENV = "FINERACT_PENTAHO_CUSTOM_FONTS_DIR";

    /** Default sub-directory under the Pentaho reports base dir. */
    private static final String DEFAULT_FONTS_SUBDIR = "fonts";

    private static volatile boolean registered = false;

    private CustomFontRegistrar() {
        // utility class
    }

    /**
     * Registers all TTF/OTF fonts found in the configured fonts directory.
     *
     * <p>
     * This method is idempotent — subsequent calls after the first successful
     * registration are no-ops.
     *
     * @param pentahoReportsBaseDir the base directory where Pentaho reports are
     *                              stored; used as fallback location for fonts
     *                              (i.e. {@code <baseDir>/fonts/})
     */
    public static synchronized void registerCustomFonts(final String pentahoReportsBaseDir) {
        if (registered) {
            LOG.debug("Custom fonts already registered; skipping.");
            return;
        }

        final File fontsDir = resolveFontsDirectory(pentahoReportsBaseDir);
        if (fontsDir == null || !fontsDir.isDirectory()) {
            LOG.info("No custom fonts directory found. Skipping custom font registration.");
            return;
        }

        LOG.info("Registering custom fonts from: {}", fontsDir.getAbsolutePath());

        final List<File> fontFiles = collectFontFiles(fontsDir);
        if (fontFiles.isEmpty()) {
            LOG.info("No TTF/OTF font files found in: {}", fontsDir.getAbsolutePath());
            return;
        }

        // 1. Register with TrueTypeFontRegistry (for font metrics)
        registerWithTrueTypeRegistry(fontFiles);

        // 2. Register with ITextFontRegistry (for PDF output)
        registerWithITextRegistry(fontFiles);

        // 3. Register with AWT GraphicsEnvironment (for HTML/XLS/XLSX output)
        registerWithAwtEnvironment(fontFiles);

        registered = true;
        LOG.info("Successfully registered {} custom font file(s).", fontFiles.size());
    }

    /**
     * Resolves the directory that contains custom font files.
     * Priority:
     * <ol>
     *   <li>System property {@value #FONTS_DIR_PROPERTY}</li>
     *   <li>Environment variable {@value #FONTS_DIR_ENV}</li>
     *   <li>{@code <pentahoReportsBaseDir>/fonts/}</li>
     * </ol>
     */
    private static File resolveFontsDirectory(final String pentahoReportsBaseDir) {
        // 1. System property
        String dirPath = System.getProperty(FONTS_DIR_PROPERTY);

        // 2. Environment variable
        if (dirPath == null || dirPath.isBlank()) {
            dirPath = System.getenv(FONTS_DIR_ENV);
        }

        // 3. Fallback: <pentahoReportsBaseDir>/fonts/
        if ((dirPath == null || dirPath.isBlank()) && pentahoReportsBaseDir != null) {
            dirPath = pentahoReportsBaseDir + File.separator + DEFAULT_FONTS_SUBDIR;
        }

        if (dirPath == null || dirPath.isBlank()) {
            return null;
        }

        final File dir = new File(dirPath);
        if (!dir.exists()) {
            LOG.debug("Custom fonts directory does not exist: {}", dirPath);
            return null;
        }
        return dir;
    }

    /**
     * Recursively collects all .ttf, .otf, and .ttc files from the given directory.
     */
    private static List<File> collectFontFiles(final File directory) {
        final List<File> result = new ArrayList<>();
        collectFontFilesRecursive(directory, result);
        return result;
    }

    private static void collectFontFilesRecursive(final File directory, final List<File> accumulator) {
        final File[] files = directory.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(final File dir, final String name) {
                final String lower = name.toLowerCase();
                return lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc");
            }
        });

        if (files == null) {
            return;
        }

        for (final File file : files) {
            if (file.isDirectory()) {
                collectFontFilesRecursive(file, accumulator);
            } else if (file.isFile() && file.canRead() && file.length() > 0) {
                accumulator.add(file);
            }
        }
    }

    /**
     * Registers fonts with the Pentaho {@link TrueTypeFontRegistry}.
     * This makes font metrics available to the reporting engine.
     */
    private static void registerWithTrueTypeRegistry(final List<File> fontFiles) {
        try {
            final TrueTypeFontRegistry ttRegistry = new TrueTypeFontRegistry();
            // initialize() scans default system font paths + extra-font-dirs config
            ttRegistry.initialize();

            for (final File fontFile : fontFiles) {
                try {
                    ttRegistry.registerFontFile(fontFile, "UTF-8");
                    LOG.debug("Registered TTF with TrueTypeFontRegistry: {}", fontFile.getName());
                } catch (Exception e) {
                    LOG.warn("Failed to register font with TrueTypeFontRegistry: {} — {}",
                            fontFile.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to initialize TrueTypeFontRegistry", e);
        }
    }

    /**
     * Registers fonts with the iText {@link ITextFontRegistry} used for PDF output.
     * We create a dedicated {@link TrueTypeFontRegistry}, load the fonts into it,
     * and then add it to the compound {@link ITextFontRegistry}.
     */
    private static void registerWithITextRegistry(final List<File> fontFiles) {
        try {
            final ITextFontRegistry itextRegistry = BaseFontModule.getFontRegistry();

            // Create a dedicated TrueType registry for our custom fonts
            final TrueTypeFontRegistry customTtRegistry = new TrueTypeFontRegistry();
            customTtRegistry.initialize();

            for (final File fontFile : fontFiles) {
                try {
                    customTtRegistry.registerFontFile(fontFile, "UTF-8");
                    LOG.debug("Registered TTF for PDF (iText): {}", fontFile.getName());
                } catch (Exception e) {
                    LOG.warn("Failed to register font for PDF output: {} — {}",
                            fontFile.getName(), e.getMessage());
                }
            }

            // Add our custom registry to the compound iText registry
            itextRegistry.addRegistry(customTtRegistry);
            LOG.debug("Added custom TrueTypeFontRegistry to ITextFontRegistry.");

        } catch (Exception e) {
            LOG.error("Failed to register fonts with ITextFontRegistry", e);
        }
    }

    /**
     * Registers fonts with the AWT {@link GraphicsEnvironment}.
     * This is required for HTML, XLS, and XLSX output targets which use
     * Java2D for rendering.
     */
    private static void registerWithAwtEnvironment(final List<File> fontFiles) {
        try {
            final GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();

            for (final File fontFile : fontFiles) {
                try {
                    // Only register .ttf and .otf (not .ttc collections) with AWT
                    final String name = fontFile.getName().toLowerCase();
                    if (name.endsWith(".ttc")) {
                        LOG.debug("Skipping TTC collection for AWT registration: {}", fontFile.getName());
                        continue;
                    }

                    final Font awtFont = Font.createFont(Font.TRUETYPE_FONT, fontFile);
                    ge.registerFont(awtFont);
                    LOG.debug("Registered font with AWT GraphicsEnvironment: {} (family: {})",
                            fontFile.getName(), awtFont.getFamily());
                } catch (FontFormatException | IOException e) {
                    LOG.warn("Failed to register font with AWT: {} — {}",
                            fontFile.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to register fonts with AWT GraphicsEnvironment", e);
        }
    }

    /**
     * Returns whether custom fonts have already been registered.
     */
    public static boolean isRegistered() {
        return registered;
    }

    /**
     * Resets the registration state. Intended for testing only.
     */
    static void resetForTesting() {
        registered = false;
    }
}