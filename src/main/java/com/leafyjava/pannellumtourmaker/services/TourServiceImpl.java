package com.leafyjava.pannellumtourmaker.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.leafyjava.pannellumtourmaker.domains.Exif;
import com.leafyjava.pannellumtourmaker.domains.GPano;
import com.leafyjava.pannellumtourmaker.domains.PhotoMeta;
import com.leafyjava.pannellumtourmaker.domains.Scene;
import com.leafyjava.pannellumtourmaker.domains.Tour;
import com.leafyjava.pannellumtourmaker.exceptions.ExternalCommandException;
import com.leafyjava.pannellumtourmaker.exceptions.TourAlreadyExistsException;
import com.leafyjava.pannellumtourmaker.exceptions.TourNotFoundException;
import com.leafyjava.pannellumtourmaker.exceptions.UnsupportedFileTreeException;
import com.leafyjava.pannellumtourmaker.repositories.TourRepository;
import com.leafyjava.pannellumtourmaker.storage.configs.StorageProperties;
import com.leafyjava.pannellumtourmaker.storage.services.StorageService;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.sanselan.ImageReadException;
import org.apache.sanselan.Sanselan;
import org.apache.sanselan.formats.jpeg.JpegImageMetadata;
import org.apache.sanselan.formats.tiff.TiffImageMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.leafyjava.pannellumtourmaker.configs.WebConfig.TOURS;
import static com.leafyjava.pannellumtourmaker.utils.FileConstants.MULTIRES;

@Service
public class TourServiceImpl implements TourService{

    private static final Logger LOGGER = LoggerFactory.getLogger(TourServiceImpl.class);

    @Value("${application.domain}")
    private String domain;

    @Value("${spring.application.path}")
    private String path;

    @Value("${application.nona}")
    private String nona;

    @Value("${application.photo-preview.width}")
    private int previewWidth;

    @Value("${application.photo-preview.height}")
    private int previewHeight;

    @Value("${application.tile-generate-script}")
    private String generateScript;

    @Value("${application.system-command}")
    private String systemCommand;

    private StorageService storageService;
    private StorageProperties storageProperties;
    private TourRepository tourRepository;
    private Environment environment;

    @Autowired
    public TourServiceImpl(final Environment environment,
                           final StorageService storageService,
                           final StorageProperties storageProperties,
                           final TourRepository tourRepository) {
        this.environment = environment;
        this.storageService = storageService;
        this.storageProperties = storageProperties;
        this.tourRepository = tourRepository;
    }

    @Override
    public void createTourFromMultires(final String tourName,  Map<String, PhotoMeta> metaMap, String mapPath, int northOffset) {
        Path tourPath = Paths.get(storageProperties.getTourLocation()).resolve(tourName).resolve(MULTIRES);
        try {
            Set<Scene> scenes = Files.walk(tourPath, 1)
                .filter(path -> !path.getFileName().toString().equalsIgnoreCase(MULTIRES) &&
                    !path.getFileName().toString().equalsIgnoreCase(".DS_Store"))
                .map(scenePath -> mapConfigToScene(scenePath, metaMap, northOffset))
                .collect(Collectors.toSet());

            Tour tour = new Tour();
            tour.setName(tourName);
            tour.setScenes(scenes);
            tour.setMapPath(mapPath);
            if (scenes.size() > 0) {
                tour.setFirstScene(scenes.iterator().next().getId());
            }

            if(tourRepository.findOne(tourName) != null) {
                throw new TourAlreadyExistsException(tourName + " already exists in the tour collection.");
            } else {
                tourRepository.insert(tour);
            }

        } catch (IOException e) {
            throw new UnsupportedFileTreeException("Multi-resolution directory is not found.", e);
        }
    }

    @Override
    public void addToTourFromMultires(final String tourName,  Map<String, PhotoMeta> metaMap, int northOffset) {
        Path tourPath = Paths.get(storageProperties.getTourLocation()).resolve(tourName).resolve(MULTIRES);
        try {
            Set<Scene> scenes = Files.walk(tourPath, 1)
                .filter(path -> !path.getFileName().toString().equalsIgnoreCase(MULTIRES) &&
                    !path.getFileName().toString().equalsIgnoreCase(".DS_Store"))
                .map(scenePath -> mapConfigToScene(scenePath, metaMap, northOffset))
                .collect(Collectors.toSet());

            Tour tour = tourRepository.findOne(tourName);
            if (tour == null) {
                throw new TourNotFoundException("Could not find tour " + tourName);
            }
            tour.addScenes(scenes);
            if (tour.getFirstScene() == null && scenes.size() > 0) {
                tour.setFirstScene(scenes.iterator().next().getId());
            }
            tourRepository.save(tour);

        } catch (IOException e) {
            throw new UnsupportedFileTreeException("Multi-resolution directory is not found.", e);
        }
    }


    @Override
    public Map<String, PhotoMeta> convertToMultiresFromEquirectangular(final String tourName) {
        Path equirectangularPath = Paths.get(storageProperties.getEquirectangularLocation()).resolve(tourName);
        Map<String, PhotoMeta> metaMap = new HashMap<>();

        try {
            Files.walk(equirectangularPath, 2)
                .filter(path -> path.toString().toLowerCase().endsWith(".jpg"))
                .forEach(path -> {
                    extractMeta(metaMap, path.toFile());
                    makeTiles(tourName, path);
                    makePreview(tourName, path);
                });
            FileSystemUtils.deleteRecursively(equirectangularPath.toFile());
        } catch (IOException e) {
            throw new UnsupportedFileTreeException("Failed to read equirectangular directory.", e);
        }

        return metaMap;
    }


    @Override
    public File createTempFileFromMultipartFile(final MultipartFile file) {
        return storageService.createTempFileFromMultipartFile(file);
    }

    @Override
    public String getMapPath(String tourName, File mapFile) {
        if (mapFile == null) return null;

        return domain + path + "/" + TOURS + "/" + tourName + "/" + "map." + FilenameUtils.getExtension(mapFile.getName());
    }

    @Override
    public List<Tour> findAllTours() {
        return tourRepository.findAll();
    }

    @Override
    public List<Tour> findAllToursWithBasic() {
        return tourRepository.findAllWithBasic();
    }

    @Override
    public List<Tour> findToursByGroupWithBasic(String groupName) {
        return tourRepository.findByGroupNameWithBasic(groupName);
    }

    @Override
    public List<String> findAllTourNames() {
        return tourRepository.findAll().stream().map(Tour::getName).collect(Collectors.toList());
    }

    @Override
    public Tour findOne(final String name) {
        return tourRepository.findOne(name);
    }

    @Override
    public Tour save(final Tour tour) {
        return tourRepository.save(tour);
    }

    @Override
    public void deleteScene(final String name, final String sceneId) {
        Tour tour = tourRepository.findOne(name);

        if (tour == null) return;

        tour.deleteScene(sceneId);

        if (sceneId.equalsIgnoreCase(tour.getFirstScene())) {
            if (tour.getScenes().size() > 0) {
                tour.setFirstScene(tour.getScenes().iterator().next().getId());
            } else {
                tour.setFirstScene(null);
            }
        }

        tourRepository.save(tour);
    }

    @Override
    public void delete(final Tour tour) {
        tourRepository.delete(tour);
    }

    @Override
    public boolean exists(final String name) {
        return tourRepository.exists(name);
    }

    private void makeTiles(final String tourName, final Path path) {
        Path output = Paths.get(storageProperties.getTourLocation())
            .resolve(tourName).resolve(MULTIRES).resolve(FilenameUtils.getBaseName(path.toString()));

        File tempFile = null;
        File outputFile = output.toFile();
        if (outputFile.exists()) {
            try {
                tempFile = FileUtils.getTempDirectory().toPath().resolve(UUID.randomUUID().toString()).toFile();
                FileUtils.moveDirectory(outputFile, tempFile);
            } catch (IOException e) {
                throw new ExternalCommandException("Failed to move temp file", e);
            }
        }

        String cmd;
        if (Arrays.asList(environment.getActiveProfiles()).contains("dev")) {
            cmd = systemCommand + " python " + generateScript  + " -o " + output +
                " -n " + nona + " " + path;
        } else {
            cmd = systemCommand + " python \"" + generateScript  + "\" -o \"" + output +
                "\" -n \"" + nona + "\" \"" + path + "\"";
        }

        try {
            Process process = Runtime.getRuntime().exec(cmd);
            int result = process.waitFor();
            if (result != 0) {
                if (tempFile != null) {
                    FileUtils.moveDirectory(tempFile, outputFile);
                }

                int len;
                if ((len = process.getErrorStream().available()) > 0) {
                    byte[] buf = new byte[len];
                    process.getErrorStream().read(buf);
                    System.err.println("Command error:\t\""+new String(buf)+"\"");
                }

                throw new ExternalCommandException("Command failed: " + String.join(" ", cmd));
            }
        } catch (IOException | InterruptedException e) {
            if (tempFile != null) {
                try {
                    FileUtils.moveDirectory(tempFile, outputFile);
                } catch (IOException e1) {
                    throw new ExternalCommandException("Failed to restore the original file", e);
                }
            }
            throw new ExternalCommandException("Command failed: " + String.join(" ", cmd), e);
        }
        finally {
            if (tempFile != null) {
                tempFile.delete();
            }
        }
    }

    private void makePreview(final String tourName, final Path path) {
        try {
            BufferedImage originalImage = ImageIO.read(path.toFile());
            int type = originalImage.getType() == 0? BufferedImage.TYPE_INT_ARGB : originalImage.getType();

            BufferedImage resizeImagePng = resizeImage(originalImage, type);

            Path output = Paths.get(storageProperties.getTourLocation())
                .resolve(tourName).resolve(MULTIRES).resolve(FilenameUtils.getBaseName(path.toString())).resolve("preview.png");
            ImageIO.write(resizeImagePng, "png", output.toFile());
        } catch (IOException e) {
            LOGGER.error("Failed to create preview image for " + path);
        }
    }

    private Scene mapConfigToScene(Path scenePath, Map<String, PhotoMeta> metaMap, int northOffset) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            Path config = scenePath.resolve("config.json");
            Scene scene = mapper.readValue(config.toFile(), Scene.class);
            String sceneId = scenePath.getFileName().toString();
            scene.setId(sceneId);
            scene.setTitle(sceneId);
            scene.setType("multires");
            scene.setNorthOffset(northOffset);

            if (metaMap != null) {
                scene.setPhotoMeta(metaMap.get(sceneId));
            }

            String basePath = domain + path + "/" + scenePath.toString()
                .replace(storageProperties.getTourLocation(), TOURS)
                .replace("\\", "/");

            LOGGER.debug("Base Path: " + basePath);

            scene.getMultiRes().setBasePath(basePath);
            scene.setHotSpots(new ArrayList<>());
            return scene;
        } catch (IOException e) {
            throw new UnsupportedFileTreeException("Failed to read: " + scenePath, e);
        }
    }

    private void extractMeta(final Map<String, PhotoMeta> map, final File file) {
        try {
            PhotoMeta photoMeta = new PhotoMeta();

            photoMeta.setExif(extractExif(file));
            photoMeta.setGPano(extractGPano(file));

            map.put(FilenameUtils.getBaseName(file.getName()), photoMeta);


        } catch (ImageReadException | IOException e) {
            LOGGER.warn("Failed to read meta data from image: " + file.getName());
        }
    }

    private GPano extractGPano(final File file) throws ImageReadException, IOException {
        GPano gPano = null;
        String xmpXml = Sanselan.getXmpXml(file);
        if (xmpXml != null && !xmpXml.isEmpty()) {
            XmlMapper mapper = new XmlMapper();
            String cleanedXml = extractText(xmpXml);
            gPano = mapper.readValue(cleanedXml, GPano.class);
        }
        return gPano;
    }

    private Exif extractExif(final File file) throws ImageReadException, IOException {
        Exif result = null;
        JpegImageMetadata metadata = (JpegImageMetadata) Sanselan.getMetadata(file);
        TiffImageMetadata exif = metadata.getExif();
        if (exif != null) {
            TiffImageMetadata.GPSInfo gps = exif.getGPS();
            if (gps != null) {
                final double longitude = gps.getLongitudeAsDegreesEast();
                final double latitude = gps.getLatitudeAsDegreesNorth();

                result = new Exif(longitude, latitude);
            }
        }
        return result;
    }

    private static String extractText(String content) {
        Pattern p = Pattern.compile("(<GPano:.*>)",
            Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            sb.append(m.group(1).replace("GPano:", ""));
        }
        return "<GPano>" + sb.toString() + "</GPano>";
    }

    private BufferedImage resizeImage(BufferedImage originalImage, int type){
        BufferedImage resizedImage = new BufferedImage(previewWidth, previewHeight, type);
        Graphics2D g = resizedImage.createGraphics();
        g.drawImage(originalImage, 0, 0, previewWidth, previewHeight, null);
        g.dispose();

        return resizedImage;
    }
}
