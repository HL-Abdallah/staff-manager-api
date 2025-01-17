package com.example.staffmanagerapi.service;

import com.example.staffmanagerapi.dto.activity.ActivityDto;
import com.example.staffmanagerapi.dto.activity.out.CompteRenduActiviteOutDto;
import com.example.staffmanagerapi.dto.activity.out.CustomerInvoiceDetail;
import com.example.staffmanagerapi.enums.ActivityCategoryEnum;
import com.example.staffmanagerapi.exception.MultipleSocietiesFoundException;
import com.example.staffmanagerapi.exception.NoMissionFoundForCollaborator;
import com.example.staffmanagerapi.model.*;
import com.example.staffmanagerapi.repository.ActivityRepository;
import com.example.staffmanagerapi.repository.CollaboratorRepository;
import com.example.staffmanagerapi.repository.InvoiceRepository;
import com.example.staffmanagerapi.repository.SocietyRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

import static com.example.staffmanagerapi.utils.Constants.*;

@Service
@Slf4j
public class ActivityService {

    private final ActivityRepository activityRepository;
    private final CollaboratorService collaboratorService;
    private final MissionService missionService;
    private final CollaboratorRepository collaboratorRepository;
    private final SocietyRepository societyRepository;
    private final JasperReportService jasperReportService;
    private final AmazonS3Service amazonS3Service;
    private final InvoiceRepository invoiceRepository;

    @Value("${bucket.factures}")
    private String factureBucket;

    public ActivityService(
            ActivityRepository activityRepository1,
            CollaboratorService collaboratorService1,
            MissionService missionService1,
            CollaboratorRepository collaboratorRepository,
            SocietyRepository societyRepository,
            JasperReportService jasperReportService,
            AmazonS3Service amazonS3Service,
            InvoiceRepository invoiceRepository) {
        this.activityRepository = activityRepository1;
        this.collaboratorService = collaboratorService1;
        this.missionService = missionService1;
        this.collaboratorRepository = collaboratorRepository;
        this.societyRepository = societyRepository;
        this.jasperReportService = jasperReportService;
        this.amazonS3Service = amazonS3Service;
        this.invoiceRepository = invoiceRepository;
    }

    public static Double sumDaysByCategory(
            List<Activity> activities,
            Set<ActivityCategoryEnum> categories
    ) {
        return hoursToDays(
                activities
                        .stream()
                        .filter(activity ->
                                categories.stream().anyMatch(cat -> activity.getCategory() == cat)
                        )
                        .mapToInt(Activity::getQuantity)
                        .sum()
        );
    }

    public static Double hoursToDays(Integer hours) {
        // Create a BigDecimal object from the input value and round the BigDecimal value to three decimal places.
        BigDecimal decimalValue = new BigDecimal(hours)
                .divide(BigDecimal.valueOf(HOURS_TO_DAY_RATIO), 3, RoundingMode.HALF_UP);

        Double finalResult = decimalValue.doubleValue();

        log.debug("conveting {} hours to {} days ", hours, finalResult);
        return finalResult;
    }

    public List<Activity> createActivities(User user, List<ActivityDto> data) {
        Optional<Collaborator> collaborator =
                this.collaboratorService.findCollaboratorByEmail(user.getEmail());

        if (!collaborator.isPresent()) throw new EntityNotFoundException(
                "Collaborator doesn't exist."
        );

        List<Mission> missions =
                this.missionService.getCollaboratorMissions(collaborator.get());

        List<Activity> records = data
                .stream()
                .map(row -> {
                    List<Mission> activeMissions = missions
                            .stream()
                            .filter(missionRow -> {
                                return (
                                        row
                                                .getDate()
                                                .isAfter(missionRow.getStartingDateMission().minusDays(1)) &&
                                                row
                                                        .getDate()
                                                        .isBefore(missionRow.getEndingDateMission().plusDays(1))
                                );
                            })
                            .toList();

                    return Activity
                            .builder()
                            .date(row.getDate())
                            .quantity(row.getQuantity())
                            .category(row.getCategory())
                            .comment(row.getComment())
                            .collaborator(collaborator.get())
                            .mission(
                                    (activeMissions.size() > 0 && shouldAddMission(row.getCategory()))
                                            ? activeMissions.get(0)
                                            : null
                            )
                            .build();
                })
                .toList();

        return this.activityRepository.saveAll(records);
    }

    /**
     * <h1>Etapes a suivre:</h1>
     * <ol>
     *     <li>fetchAll activités de la base</li>
     *     <li>filtrer la liste pour le mois courant</li>
     *     <li>creer une <code>Map</code> de Collaborateur et sa liste d'activités </li>
     *     <li>parcourir la <code>Map</code>, et pour chaque collaborateur faire le calcul selon les régles définies dans l'US-141</li>
     *     <li>construire un DTO et le retourner comme réponse</li>
     * </ol>
     *
     * @return le compte rendu d'activité qui est une <code>List of CompteRenduActiviteOutDto </code>
     * @author Abdallah
     * @see #hoursToDays(Integer)
     * @see #sumDaysByCategory(List, Set)
     * @since 7/23/2023
     */
    public List<CompteRenduActiviteOutDto> getCurrentMonthCRA() {
        // constants
        YearMonth currentMonth = YearMonth.now();
        log.info(
                "Attempting to fetch compte-rendu-activité for current month : {} ...",
                currentMonth
        );
        // traitement activitiés
        Map<Collaborator, List<Activity>> activities =
                this.activityRepository.findAll()
                        .stream()
                        .filter(activity -> activity.getCollaborator() != null) // remove activities with no collaborator
                        .filter(activity ->
                                YearMonth.from(activity.getDate()).equals(currentMonth)
                        ) // filter to current month only
                        .collect(Collectors.groupingBy(Activity::getCollaborator)); // turn List into a Map

        // une fois groupé par collaborateur dans un Map<Collaborator,List<Activity>>
        // on map sur un DTO List<CompteRenduActiviteOutDto>

        List<CompteRenduActiviteOutDto> cra = new ArrayList<>();

        activities.forEach((collaborator, activityList) -> {
            log.debug(
                    "Processing Collaborator: {}",
                    collaborator.getFirstName() + collaborator.getLastName()
            );
            log.debug("Number of activities: {} ", activityList.size());

            Double declaredDays = sumDaysByCategory(
                    activityList,
                    DECLARED_DAYS_CATEGORIES
            );
            Double billedDays = sumDaysByCategory(
                    activityList,
                    BILLED_DAYS_CATEGORIES
            );
            Double rttRedemption = sumDaysByCategory(
                    activityList,
                    RTT_REDEMPTION_DAYS_CATEGORIES
            );
            Double absenceDays = sumDaysByCategory(
                    activityList,
                    ABSENCE_DAYS_CATEGORIES
            );
            Double extraHoursInDays = sumDaysByCategory(
                    activityList,
                    EXTRA_HOURS_IN_DAYS_CATEGORIES
            );
            Double onCallHoursInDays = sumDaysByCategory(
                    activityList,
                    ON_CALL_HOURS_IN_DAYS_CATEGORIES
            );

            CompteRenduActiviteOutDto dto = CompteRenduActiviteOutDto
                    .builder()
                    .collaboratorLastName(collaborator.getLastName())
                    .collaboratorFirstName(collaborator.getFirstName())
                    .declaredDays(declaredDays)
                    .billedDays(billedDays)
                    .rttRedemption(rttRedemption)
                    .absenceDays(absenceDays)
                    .extraHoursInDays(extraHoursInDays)
                    .onCallHoursInDays(onCallHoursInDays)
                    .collaboratorId(collaborator.getId())
                    .build();

            log.debug(
                    "Processing finished for Collaborator: {}",
                    collaborator.getFirstName() + collaborator.getLastName()
            );
            log.debug("CRA item to append : {}", dto);

            cra.add(dto);
        });
        return cra;
    }

    Boolean shouldAddMission(ActivityCategoryEnum category) {
        if (category == ActivityCategoryEnum.JOUR_TRAVAILLE) return true;
        if (category == ActivityCategoryEnum.HEURE_SUPPLEMENTAIRE) return true;
        if (category == ActivityCategoryEnum.ASTREINTE) return true;

        return false;
    }

    public void validerCRAAndGenerateInvoice(Long collaboratorId) {

        Collaborator collaborator = this.collaboratorRepository.findById(collaboratorId)
                .orElseThrow(() -> new EntityNotFoundException("Le collaborateur possédant l'ID " + collaboratorId + " n'existe pas"));
        log.info("Collaborateur possedant l'ID {} existe en base, nom : {} ", collaboratorId, collaborator.getFirstName() + collaborator.getLastName());
        /**
         * Chaque key/value reference un Client + les activities associés
         */
        YearMonth currentMonth = YearMonth.now();
        Map<Mission, List<Activity>> missionActivitiesMap = this.activityRepository.findAll()
                .stream()
                .filter(activity -> YearMonth.from(activity.getDate()).equals(currentMonth)) // data du mois courant only
                .filter(activity -> Objects.equals(activity.getCollaborator().getId(), collaboratorId)) // filter only to the needed collaborator
                .filter(activity -> activity.getMission() != null) // old data contien des lignes ou l'activity n'a pas de mission, a filtrer
                .collect(Collectors.groupingBy(Activity::getMission));

        log.info("Traitemant d'un total de {} missions pour {} ", missionActivitiesMap.size(), collaborator.getFirstName() + collaborator.getLastName());
        if (missionActivitiesMap.size() == 0) {
            String message = "Le Collaborateur " + collaborator.getFirstName() + " " + collaborator.getLastName() + " n'a aucune mission pendant la période " + currentMonth;
            log.info(message);
            throw new NoMissionFoundForCollaborator(message);
        }

        missionActivitiesMap.forEach((mission, activities) -> {
            log.info("Traitement de mission(s) : '{}' qui possede {} activité(s) ...", mission.getNameMission(), activities.size());
            /**
             * Step 1: generer un objet CustomerInvoiceDetail, c'est le contenu du tableau,
             * a ajouter comme datasource a Jasper
             */
            Double billedDays = sumDaysByCategory(
                    activities,
                    BILLED_DAYS_CATEGORIES
            );
            Double extraHoursInDays = sumDaysByCategory(
                    activities,
                    EXTRA_HOURS_IN_DAYS_CATEGORIES
            );
            Double onCallHoursInDays = sumDaysByCategory(
                    activities,
                    ON_CALL_HOURS_IN_DAYS_CATEGORIES
            );

            List<CustomerInvoiceDetail> invoiceDetails = new ArrayList<>();
            invoiceDetails.add(
                    new CustomerInvoiceDetail(
                            ActivityCategoryEnum.JOUR_TRAVAILLE,
                            billedDays,
                            PRIX_UNITAIRE_JOUR_TRAVAILLE, // 100 £ pour les jours tavaillés, a ramener de la base plutard, la c'est un constant
                            billedDays * PRIX_UNITAIRE_JOUR_TRAVAILLE
                    )
            );
            invoiceDetails.add(
                    new CustomerInvoiceDetail(
                            ActivityCategoryEnum.HEURE_SUPPLEMENTAIRE,
                            extraHoursInDays,
                            PRIX_UNITAIRE_HEURES_SUP,
                            extraHoursInDays * PRIX_UNITAIRE_HEURES_SUP
                    )
            );
            invoiceDetails.add(
                    new CustomerInvoiceDetail(
                            ActivityCategoryEnum.ASTREINTE,
                            onCallHoursInDays,
                            PRIX_UNITAIRE_ASTREINTES,
                            onCallHoursInDays * PRIX_UNITAIRE_ASTREINTES
                    )
            );
            /**
             * Step 2 : generer le footer ou il y'a les totals HT et TTC ,
             * c'est un simple Map, a ajouter comme parameter a Jasper
             */
            log.info("Détails facture : {} ", invoiceDetails);
            log.info("Génération des parametres du raport .. ");
            List<Society> societies = this.societyRepository.findAll();
            if (societies.size() > 1) {
                throw new MultipleSocietiesFoundException("On s'attend à ce qu'une société soit renvoyée de la base, on en a trouvé plusieurs");
            } else if (societies.isEmpty()) {
                throw new EntityNotFoundException("Aucune Société en base, la génération de la facture client dépends du TVA de la société ainsi que d'autres informations");
            }

            Double totalHT = invoiceDetails.stream()
                    .mapToDouble(CustomerInvoiceDetail::getAmountexcludingVAT)
                    .sum();
            Double montantTVA = totalHT * 0.2;
            Double totalTTC = totalHT + montantTVA; // totalTTC = totalHT + totalHT * ( tva / 100 )

            Map<String, Object> extraReportParams = new HashMap<>();
            extraReportParams.put("totalHT", totalHT);
            extraReportParams.put("tva", montantTVA);
            extraReportParams.put("totalTTC", totalTTC);

            // client details :
            extraReportParams.put("customer-name", mission.getCustomer().getCustomerName());
            extraReportParams.put("customer-adress", mission.getCustomer().getCustomerAddress());
            /**
             * Step 3 : generer le report in a temp directory
             */
            String pdfName = mission.getCustomer().getCustomerName().replace(" ", "_") + "-"
                    + currentMonth.getMonthValue() + "-"
                    + currentMonth.getYear() + "-"
                    + collaborator.getFirstName().replace(" ", "_") + "-"
                    + collaborator.getLastName().replace(" ", "_");
            String pdfReportName = pdfName + ".pdf";
            log.info("Génération du rapport pdf au nom : {}", pdfReportName);
            byte[] pdfBytes =  this.jasperReportService.generateReport("reports/customerInvoice.jrxml", invoiceDetails, extraReportParams, pdfName);

            saveInvoiceInDatabase(currentMonth, mission, pdfReportName);
            saveToS3AndDeleteFromDirectory(pdfReportName, pdfBytes);
        });
    }

    private void saveInvoiceInDatabase(YearMonth currentMonth, Mission mission, String pdfReportName) {
        Invoice invoice = Invoice.builder()
                .name(pdfReportName)
                .createdAt(LocalDate.now())
                .customer(mission.getCustomer())
                .collaborator(mission.getCollaborator())
                .monthYear(currentMonth.atDay(1))
                .build();
        invoiceRepository.save(invoice);
    }

    private void saveToS3AndDeleteFromDirectory(String pdfReportName, byte[] pdfBytes) {
        log.info("Tentative de upload le rapport {} a S3", pdfReportName);
        try {
            uploadFromByteArray(factureBucket, pdfBytes, pdfReportName);
        } catch (IOException e) {
            throw new EntityNotFoundException("Erreur lors de génération du rapport " + pdfReportName);
        }
        log.info("Rapport chargé a S3 avec success !");
        viderJasperTempDirectory();
    }

    public void viderJasperTempDirectory(){
        log.info("Suppression des fichiers du dossier temp ...");
        String directoryPath = "src/main/resources/reports/temp/";
        File directory = new File(directoryPath);

        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles();

            if (files != null) {
                for (File file : files) {
                    if (file.isFile()) {
                        file.delete();
                        log.info("Fichier supprimé :  " + file.getName());
                    }
                }
            } else {
                log.info("Dossier Jasper Temp est vide, rien a supprimer");
            }
        } else {
            log.info("Le dossier n'existe pas, ou ce n'est pas un dossier");
        }
    }

    public void uploadFromByteArray(String bucketName, byte[] byteArray, String s3FileName) throws IOException {
        InputStream inputStream = new ByteArrayInputStream(byteArray);

        MultipartFile multipartFile = new MultipartFile() {
            @Override
            public String getName() {
                return s3FileName;
            }

            @Override
            public String getOriginalFilename() {
                return s3FileName;
            }

            @Override
            public String getContentType() {
                return "application/pdf";
            }

            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public long getSize() {
                try {
                    return inputStream.available();
                } catch (IOException e) {
                    return 0;
                }
            }

            @Override
            public byte[] getBytes() throws IOException {
                return inputStream.readAllBytes();
            }

            @Override
            public InputStream getInputStream() throws IOException {
                return inputStream;
            }

            @Override
            public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
            }
        };

        this.amazonS3Service.upload(multipartFile, bucketName, s3FileName);
    }
}
