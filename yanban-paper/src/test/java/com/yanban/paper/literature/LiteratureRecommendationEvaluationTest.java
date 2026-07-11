package com.yanban.paper.literature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yanban.paper.config.PaperLiteratureProperties;
import com.yanban.paper.domain.LiteratureCard;
import com.yanban.paper.service.PaperModelClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class LiteratureRecommendationEvaluationTest {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+", Pattern.CASE_INSENSITIVE);
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void evaluateAgainstGoldenLiteratureRankings() throws Exception {
        FixtureCatalog catalog = new FixtureCatalog(objectMapper);
        List<CorpusPaper> corpus = corpus();
        StandaloneLiteratureCardSearchService localSearch = mock(StandaloneLiteratureCardSearchService.class);
        when(localSearch.search(any(), anyInt(), any())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            Integer yearFrom = invocation.getArgument(2);
            return searchCorpus(corpus.stream().filter(CorpusPaper::localCard).toList(), query, limit, yearFrom, "local_card");
        });

        LiteratureCardCatalogService cardCatalogService = mock(LiteratureCardCatalogService.class);
        when(cardCatalogService.upsertCard(any())).thenAnswer(invocation -> catalog.toCard(invocation.getArgument(0)));

        LiteratureCardAnalysisService cardAnalysisService = mock(LiteratureCardAnalysisService.class);
        ObjectProvider<PaperModelClient> modelClientProvider = mock(ObjectProvider.class);
        when(modelClientProvider.getIfAvailable()).thenReturn(new PlannerModelClient());

        LiteratureRecommendationService service = new LiteratureRecommendationService(
                List.of(new FixtureSource("openalex", corpus), new FixtureSource("arxiv", corpus)),
                localSearch,
                cardCatalogService,
                cardAnalysisService,
                modelClientProvider,
                objectMapper,
                new PaperLiteratureProperties()
        );

        List<CaseReport> reports = new ArrayList<>();
        for (EvalCase evalCase : evalCases()) {
            LiteratureRecommendationService.RecommendationResult result = service.recommend(
                    new LiteratureRecommendationService.RecommendationRequest(
                            evalCase.query(),
                            evalCase.goal(),
                            evalCase.claims(),
                            evalCase.yearFrom(),
                            10,
                            null,
                            null,
                            false,
                            evalCase.existingBibtex(),
                            null
                    )
            );
            reports.add(score(evalCase, result));
        }

        double averageScore = reports.stream().mapToDouble(CaseReport::score).average().orElse(0);
        EvalReport report = new EvalReport(Instant.now().toString(), round(averageScore), reports);
        writeReports(report);

        assertThat(averageScore).isGreaterThanOrEqualTo(3.5);
        assertThat(reports).allSatisfy(item -> assertThat(item.score()).isGreaterThanOrEqualTo(2.5));
    }

    private CaseReport score(EvalCase evalCase, LiteratureRecommendationService.RecommendationResult result) {
        List<String> goldenTitles = evalCase.goldenRanking().stream().map(GoldenItem::title).toList();
        List<String> finalTitles = result.items().stream()
                .map(LiteratureRecommendationService.RecommendationItem::title)
                .toList();
        List<String> top30Titles = result.rankedPreview().stream()
                .map(LiteratureRecommendationService.RecommendationDiagnosticItem::title)
                .toList();

        Set<String> goldenSet = normalizeTitles(goldenTitles);
        Set<String> finalSet = normalizeTitles(finalTitles);
        Set<String> top30Set = normalizeTitles(top30Titles);

        int finalHits = hitCount(goldenSet, finalSet);
        int top30Hits = hitCount(goldenSet, top30Set);
        double finalRecall = goldenSet.isEmpty() ? 0 : (double) finalHits / goldenSet.size();
        double top30Recall = goldenSet.isEmpty() ? 0 : (double) top30Hits / goldenSet.size();
        double orderScore = orderScore(goldenTitles, finalTitles);
        double score = Math.min(5.0, finalRecall * 2.7 + top30Recall * 1.0 + orderScore * 1.3);

        return new CaseReport(
                evalCase.id(),
                evalCase.discipline(),
                evalCase.query(),
                evalCase.standardAnswer(),
                evalCase.goldenRanking(),
                result.rawCandidateCount(),
                result.uniqueCandidateCount(),
                result.retrievalDiagnostics(),
                result.items().stream().map(item -> new ItemReport(
                        item.title(),
                        item.year(),
                        item.venue(),
                        round(item.score()),
                        item.role(),
                        item.sourceQuery(),
                        item.reason()
                )).toList(),
                result.rankedPreview().stream().map(item -> new PreviewReport(
                        item.title(),
                        round(item.score()),
                        item.role(),
                        item.sourceQuery()
                )).toList(),
                finalHits,
                top30Hits,
                round(finalRecall),
                round(top30Recall),
                round(orderScore),
                round(score)
        );
    }

    private void writeReports(EvalReport report) throws Exception {
        Path outputDir = Path.of("target", "literature-recommendation-eval");
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("report.json"), objectMapper.writeValueAsString(report));
        Files.writeString(outputDir.resolve("report.md"), markdown(report));
    }

    private String markdown(EvalReport report) {
        StringBuilder md = new StringBuilder();
        md.append("# 文献推荐标准答案对照评测\n\n");
        md.append("- 生成时间: ").append(report.generatedAt()).append("\n");
        md.append("- 平均分: ").append(report.averageScore()).append(" / 5\n");
        md.append("- 样例数量: ").append(report.cases().size()).append("\n");
        md.append("- 评测方式: 先给出标准检索答案和标准排序，再让项目文献推荐工具跑同一问题，按命中率和排序接近度打分。\n\n");
        md.append("## 总览\n\n");
        md.append("| 样例 | 学科方向 | 原始候选数 | 去重后数量 | 最终命中 | 前30命中 | 排序分 | 总分 |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|---:|\n");
        for (CaseReport item : report.cases()) {
            md.append("| ").append(item.id())
                    .append(" | ").append(escapeTable(item.discipline()))
                    .append(" | ").append(item.rawCandidateCount())
                    .append(" | ").append(item.uniqueCandidateCount())
                    .append(" | ").append(item.finalHits())
                    .append(" | ").append(item.top30Hits())
                    .append(" | ").append(item.orderScore())
                    .append(" | ").append(item.score())
                    .append(" |\n");
        }
        for (CaseReport item : report.cases()) {
            md.append("\n## ").append(item.id()).append(" - ").append(item.discipline()).append("\n\n");
            md.append("问题: `").append(item.query()).append("`\n\n");
            md.append("标准答案:\n\n");
            md.append(item.standardAnswer()).append("\n\n");
            md.append("标准排序:\n\n");
            md.append("| 标准排名 | 标题 | 为什么应该排在这里 |\n");
            md.append("|---:|---|---|\n");
            for (GoldenItem golden : item.goldenRanking()) {
                md.append("| ").append(golden.rank())
                        .append(" | ").append(escapeTable(golden.title()))
                        .append(" | ").append(escapeTable(golden.reason()))
                        .append(" |\n");
            }
            md.append("\n项目检索过程:\n\n");
            md.append("- 原始候选数: ").append(item.rawCandidateCount()).append("\n");
            md.append("- 去重后数量: ").append(item.uniqueCandidateCount()).append("\n");
            for (LiteratureRecommendationService.RetrievalDiagnosticItem diagnostic : item.retrievalDiagnostics()) {
                md.append("- ").append(diagnostic.source())
                        .append(" 问题=`").append(diagnostic.query()).append("`")
                        .append(" 搜到=").append(diagnostic.candidateCount())
                        .append(" 通过过滤=").append(diagnostic.acceptedCount());
                if (diagnostic.failed()) {
                    md.append(" 失败原因=").append(diagnostic.message());
                }
                md.append("\n");
            }
            md.append("\n项目最终推荐:\n\n");
            md.append("| 项目排名 | 标题 | 年份 | 期刊/会议 | 分数 | 角色 |\n");
            md.append("|---:|---|---:|---|---:|---|\n");
            int rank = 1;
            for (ItemReport rec : item.finalRecommendations()) {
                md.append("| ").append(rank++)
                        .append(" | ").append(escapeTable(rec.title()))
                        .append(" | ").append(rec.year() == null ? "" : rec.year())
                        .append(" | ").append(escapeTable(rec.venue()))
                        .append(" | ").append(rec.score())
                        .append(" | ").append(escapeTable(rec.role()))
                        .append(" |\n");
            }
            md.append("\n项目规则排序前 30:\n\n");
            md.append("| 排名 | 标题 | 分数 | 角色 | 来源问题 |\n");
            md.append("|---:|---|---:|---|---|\n");
            rank = 1;
            for (PreviewReport preview : item.ruleRankedTop30()) {
                md.append("| ").append(rank++)
                        .append(" | ").append(escapeTable(preview.title()))
                        .append(" | ").append(preview.score())
                        .append(" | ").append(escapeTable(preview.role()))
                        .append(" | ").append(escapeTable(preview.sourceQuery()))
                        .append(" |\n");
            }
            md.append("\n对照分数:\n\n");
            md.append("- 最终推荐命中: ").append(item.finalHits()).append(" / ").append(item.goldenRanking().size())
                    .append(", 召回率=").append(item.finalRecall()).append("\n");
            md.append("- 前30命中: ").append(item.top30Hits()).append(" / ").append(item.goldenRanking().size())
                    .append(", 召回率=").append(item.top30Recall()).append("\n");
            md.append("- 排序接近度: ").append(item.orderScore()).append("\n");
            md.append("- 总分: ").append(item.score()).append(" / 5\n");
        }
        return md.toString();
    }

    private List<EvalCase> evalCases() {
        return List.of(
                eval("LIT-01", "雷达/MIMO 雷达",
                        "MIMO radar waveform design target localization degrees of freedom",
                        "推荐 MIMO 雷达波形设计、定位和自由度分析的基础文献。",
                        "MIMO radar improves spatial diversity, waveform diversity, target localization, and degrees of freedom.",
                        2004,
                        "标准答案应优先给出 MIMO 雷达信号处理、统计 MIMO 雷达和 MIMO 雷达自由度/分辨率方面的代表性文献。",
                        List.of(
                                golden(1, "MIMO Radar Signal Processing", "系统性覆盖 MIMO 雷达波形、阵列处理和参数估计，是该问题的核心基础资料。"),
                                golden(2, "Statistical MIMO Radar with Widely Separated Antennas", "直接讨论统计 MIMO 雷达、分集增益和目标检测。"),
                                golden(3, "MIMO Radar: An Idea Whose Time Has Come", "较早提出并推广 MIMO 雷达思想，适合作为背景引用。"),
                                golden(4, "Multiple-Input Multiple-Output Radar and Imaging: Degrees of Freedom and Resolution", "直接支撑自由度、分辨率和成像能力分析。")
                        )),
                eval("LIT-02", "雷达/SAR 自动目标识别",
                        "synthetic aperture radar automatic target recognition deep learning survey",
                        "推荐 SAR ATR 深度学习和雷达目标识别综述文献。",
                        "Deep learning based SAR ATR depends on robust feature learning, target characterization, and domain shift handling.",
                        2016,
                        "标准答案应优先覆盖 SAR 图像自动目标识别、深度学习综述、目标表征和可解释性。",
                        List.of(
                                golden(1, "A Survey of Deep Learning-Based Object Detection in Remote Sensing Imagery", "覆盖遥感图像目标检测的深度学习基础，可作为 SAR ATR 背景。"),
                                golden(2, "Deep Learning for SAR Image Automatic Target Recognition: A Survey", "直接针对 SAR ATR 深度学习方法进行综述。"),
                                golden(3, "Radar Target Characterization and Deep Learning in Radar Automatic Target Recognition", "聚焦雷达目标表征和深度学习 ATR。"),
                                golden(4, "Convolutional Neural Networks for SAR Image Classification", "支撑 CNN 在 SAR 分类/识别中的基础应用。")
                        )),
                eval("LIT-03", "雷达/车载毫米波雷达感知",
                        "automotive millimeter wave radar object detection deep learning sensor fusion",
                        "推荐车载毫米波雷达目标检测和雷达视觉融合文献。",
                        "Automotive radar perception benefits from point-cloud processing, radar-camera fusion, and robust detection in adverse weather.",
                        2017,
                        "标准答案应包含车载雷达深度学习检测、雷达相机融合和毫米波点云感知。",
                        List.of(
                                golden(1, "A Survey on Deep Learning Based Object Detection in Automotive Radar", "最贴合车载雷达目标检测和深度学习主题。"),
                                golden(2, "Radar and Camera Early Fusion for Vehicle Detection in Advanced Driver Assistance Systems", "直接讨论雷达与相机早期融合。"),
                                golden(3, "Deep Learning Based 3D Object Detection for Automotive Radar and Camera", "覆盖 3D 检测和雷达相机融合。"),
                                golden(4, "PointPillars: Fast Encoders for Object Detection from Point Clouds", "虽偏激光雷达，但对点云检测结构有参考价值。")
                        )),
                eval("LIT-04", "雷达/通感一体化",
                        "joint radar communication integrated sensing and communication waveform design survey",
                        "推荐通感一体化、联合雷达通信和波形设计文献。",
                        "Integrated sensing and communication needs shared waveform design, spectrum coexistence, and joint performance trade-offs.",
                        2018,
                        "标准答案应优先覆盖联合雷达通信综述、应用路线和波形/资源分配。",
                        List.of(
                                golden(1, "Joint Radar and Communication Design: Applications, State-of-the-Art, and the Road Ahead", "系统总结联合雷达通信应用和研究路线。"),
                                golden(2, "A Survey on Joint Communication and Radar Sensing", "直接针对通信感知一体化进行综述。"),
                                golden(3, "Dual-Functional Radar-Communication Waveform Design: A Symbol-Level Precoding Approach", "支撑双功能波形设计和通信雷达权衡。"),
                                golden(4, "Integrated Sensing and Communications: Toward Dual-Functional Wireless Networks for 6G and Beyond", "覆盖 6G 通感一体化网络视角。")
                        )),
                eval("LIT-05", "雷达/毫米波人体感知",
                        "mmWave radar human activity recognition gesture vital sign sensing",
                        "推荐毫米波雷达人类活动识别、手势识别和生命体征检测文献。",
                        "mmWave radar enables privacy-preserving human sensing, gesture recognition, and non-contact vital sign monitoring.",
                        2016,
                        "标准答案应包含毫米波活动识别、手势感知和非接触生命体征检测。",
                        List.of(
                                golden(1, "milliPoint: A Point Cloud Based mmWave Radar Human Activity Recognition System", "最直接匹配毫米波雷达人类活动识别。"),
                                golden(2, "Soli: Ubiquitous Gesture Sensing with Millimeter Wave Radar", "经典毫米波手势识别系统。"),
                                golden(3, "Vital Sign Detection and Monitoring Using Doppler Radar: A Review", "生命体征检测方向的代表性综述。"),
                                golden(4, "Deep Learning for Human Activity Recognition Using Mobile and Wearable Sensor Networks", "活动识别通用深度学习背景，可辅助方法选择。")
                        )),
                eval("LIT-06", "医学影像/分割",
                        "medical image segmentation U-Net attention nnU-Net benchmark",
                        "推荐医学影像分割的基础模型和强基线文献。",
                        "Medical image segmentation should compare U-Net, attention mechanisms, and nnU-Net style self-configuring baselines.",
                        2015,
                        "标准答案应优先命中 U-Net、Attention U-Net 和 nnU-Net。",
                        List.of(
                                golden(1, "U-Net: Convolutional Networks for Biomedical Image Segmentation", "医学影像分割最核心基础模型。"),
                                golden(2, "Attention U-Net: Learning Where to Look for the Pancreas", "代表注意力机制在医学分割中的应用。"),
                                golden(3, "nnU-Net: a self-configuring method for deep learning-based biomedical image segmentation", "强基线和工程化自动配置方法。"),
                                golden(4, "TransUNet: Transformers Make Strong Encoders for Medical Image Segmentation", "代表 Transformer 医学分割路线。")
                        )),
                eval("LIT-07", "气候/遥感降水临近预报",
                        "deep learning precipitation nowcasting weather radar generative model",
                        "推荐深度学习降水临近预报和天气雷达预测文献。",
                        "Precipitation nowcasting should consider radar echo extrapolation, generative models, and benchmark evaluation.",
                        2015,
                        "标准答案应包含 ConvLSTM、生成式降水临近预报和 benchmark。",
                        List.of(
                                golden(1, "Convolutional LSTM Network: A Machine Learning Approach for Precipitation Nowcasting", "深度学习降水临近预报基础方法。"),
                                golden(2, "Skillful Precipitation Nowcasting Using Deep Generative Models of Radar", "生成式雷达降水临近预报代表作。"),
                                golden(3, "Deep Learning for Precipitation Nowcasting: A Benchmark and A New Model", "包含 benchmark 和新模型，适合评测对比。"),
                                golden(4, "FourCastNet: A Global Data-driven High-resolution Weather Model using Adaptive Fourier Neural Operators", "气象深度学习预测的重要背景。")
                        )),
                eval("LIT-08", "材料科学/材料性质预测",
                        "materials discovery crystal graph neural network property prediction",
                        "推荐晶体图神经网络和材料性质预测文献。",
                        "Materials discovery benefits from graph representations of crystals and scalable property prediction.",
                        2017,
                        "标准答案应覆盖 CGCNN、MEGNet 和图网络材料建模。",
                        List.of(
                                golden(1, "Crystal Graph Convolutional Neural Networks for an Accurate and Interpretable Prediction of Material Properties", "晶体图卷积材料性质预测基础文献。"),
                                golden(2, "Graph Networks as a Universal Machine Learning Framework for Molecules and Crystals", "统一图网络材料/分子建模框架。"),
                                golden(3, "MatErials Graph Network for the Prediction of Materials Properties", "MEGNet 材料性质预测代表作。"),
                                golden(4, "A Graph Neural Network for Materials Property Prediction", "材料属性预测图神经网络背景。")
                        )),
                eval("LIT-09", "生物信息/蛋白结构预测",
                        "protein structure prediction AlphaFold RoseTTAFold protein language model",
                        "推荐蛋白结构预测和蛋白语言模型文献。",
                        "Protein structure prediction should cite AlphaFold2, RoseTTAFold, and protein language model representation learning.",
                        2020,
                        "标准答案应优先命中 AlphaFold2、RoseTTAFold 和蛋白语言模型。",
                        List.of(
                                golden(1, "Highly accurate protein structure prediction with AlphaFold", "AlphaFold2 是蛋白结构预测核心文献。"),
                                golden(2, "Accurate prediction of protein structures and interactions using a three-track neural network", "RoseTTAFold 代表三轨网络结构预测路线。"),
                                golden(3, "Biological structure and function emerge from scaling unsupervised learning to 250 million protein sequences", "蛋白语言模型表示学习代表作。"),
                                golden(4, "Language models enable zero-shot prediction of the effects of mutations on protein function", "支撑蛋白语言模型功能预测。")
                        )),
                eval("LIT-10", "NLP/RAG 评测",
                        "retrieval augmented generation evaluation faithfulness context precision recall",
                        "推荐 RAG 系统评测、忠实性和上下文召回相关文献。",
                        "RAG evaluation needs retrieval recall, context precision, answer correctness, citation support, and faithfulness metrics.",
                        2020,
                        "标准答案应包含 RAG 基础、RAGAS、ARES 和可验证性评测。",
                        List.of(
                                golden(1, "Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks", "RAG 基础文献。"),
                                golden(2, "RAGAS: Automated Evaluation of Retrieval Augmented Generation", "RAG 自动评测指标代表作。"),
                                golden(3, "ARES: An Automated Evaluation Framework for Retrieval-Augmented Generation Systems", "RAG 评测框架代表作。"),
                                golden(4, "Evaluating Verifiability in Generative Search Engines", "支撑生成式搜索和引用可验证性评测。")
                        ))
        );
    }

    private EvalCase eval(String id,
                          String discipline,
                          String query,
                          String goal,
                          String claims,
                          Integer yearFrom,
                          String standardAnswer,
                          List<GoldenItem> goldenRanking) {
        return new EvalCase(id, discipline, query, goal, claims, yearFrom, "", standardAnswer, goldenRanking);
    }

    private GoldenItem golden(int rank, String title, String reason) {
        return new GoldenItem(rank, title, reason);
    }

    private List<CorpusPaper> corpus() {
        List<CorpusPaper> papers = new ArrayList<>();
        papers.add(paper("MIMO Radar Signal Processing", 2008, "Wiley/IEEE Press", "mimo radar waveform design target localization parameter estimation degrees of freedom array processing", 3200, true, "10.5555/mimo-signal-processing"));
        papers.add(paper("Statistical MIMO Radar with Widely Separated Antennas", 2008, "IEEE Signal Processing Magazine", "statistical mimo radar widely separated antennas spatial diversity target detection localization", 2400, true, "10.5555/statistical-mimo"));
        papers.add(paper("MIMO Radar: An Idea Whose Time Has Come", 2004, "IEEE Radar Conference", "mimo radar waveform diversity spatial diversity target detection concept", 1900, true, "10.5555/mimo-time"));
        papers.add(paper("Multiple-Input Multiple-Output Radar and Imaging: Degrees of Freedom and Resolution", 2010, "IEEE Radar", "mimo radar imaging degrees of freedom resolution target localization", 900, false, "10.5555/mimo-dof"));
        papers.add(paper("Waveform Design for MIMO Radar with Colocated Antennas", 2011, "IEEE Transactions on Signal Processing", "mimo radar waveform design colocated antennas beampattern optimization", 700, false, "10.5555/mimo-waveform"));

        papers.add(paper("A Survey of Deep Learning-Based Object Detection in Remote Sensing Imagery", 2020, "IEEE Geoscience and Remote Sensing Magazine", "deep learning object detection remote sensing imagery sar automatic target recognition", 1800, true, "10.5555/rs-detection-survey"));
        papers.add(paper("Deep Learning for SAR Image Automatic Target Recognition: A Survey", 2021, "Remote Sensing", "synthetic aperture radar sar image automatic target recognition deep learning survey", 850, true, "10.5555/sar-atr-survey"));
        papers.add(paper("Radar Target Characterization and Deep Learning in Radar Automatic Target Recognition", 2020, "IEEE Aerospace and Electronic Systems Magazine", "radar target characterization deep learning radar automatic target recognition atr", 620, true, "10.5555/radar-target-characterization"));
        papers.add(paper("Convolutional Neural Networks for SAR Image Classification", 2016, "IEEE Geoscience and Remote Sensing Letters", "convolutional neural networks sar image classification target recognition", 1100, false, "10.5555/sar-cnn"));
        papers.add(paper("Few-Shot SAR Automatic Target Recognition with Meta Learning", 2022, "IEEE TGRS", "few shot sar automatic target recognition meta learning domain shift", 240, false, "10.5555/sar-few-shot"));

        papers.add(paper("A Survey on Deep Learning Based Object Detection in Automotive Radar", 2021, "IEEE Access", "automotive radar millimeter wave object detection deep learning survey", 760, true, "10.5555/auto-radar-survey"));
        papers.add(paper("Radar and Camera Early Fusion for Vehicle Detection in Advanced Driver Assistance Systems", 2019, "IEEE ITSC", "radar camera early fusion vehicle detection automotive advanced driver assistance systems", 680, true, "10.5555/radar-camera-early"));
        papers.add(paper("Deep Learning Based 3D Object Detection for Automotive Radar and Camera", 2020, "IEEE Intelligent Vehicles", "deep learning 3d object detection automotive radar camera sensor fusion", 520, false, "10.5555/radar-camera-3d"));
        papers.add(paper("PointPillars: Fast Encoders for Object Detection from Point Clouds", 2019, "CVPR", "point cloud object detection fast encoder pillars autonomous driving", 4500, true, "10.5555/pointpillars"));
        papers.add(paper("RadarNet: Exploiting Radar for Robust Perception in Autonomous Driving", 2020, "ECCV Workshops", "radar robust perception autonomous driving object detection adverse weather", 430, false, "10.5555/radarnet"));

        papers.add(paper("Joint Radar and Communication Design: Applications, State-of-the-Art, and the Road Ahead", 2020, "IEEE Transactions on Communications", "joint radar communication design applications state of the art waveform spectrum coexistence", 1500, true, "10.5555/jrc-road"));
        papers.add(paper("A Survey on Joint Communication and Radar Sensing", 2021, "IEEE Communications Surveys and Tutorials", "joint communication radar sensing survey integrated sensing communication", 1200, true, "10.5555/jcas-survey"));
        papers.add(paper("Dual-Functional Radar-Communication Waveform Design: A Symbol-Level Precoding Approach", 2018, "IEEE Transactions on Signal Processing", "dual functional radar communication waveform design symbol level precoding", 980, false, "10.5555/dfrc-symbol"));
        papers.add(paper("Integrated Sensing and Communications: Toward Dual-Functional Wireless Networks for 6G and Beyond", 2022, "IEEE Journal on Selected Areas in Communications", "integrated sensing communications dual functional wireless networks 6g", 1600, true, "10.5555/isac-6g"));
        papers.add(paper("Radar-Communication Spectrum Sharing: A Survey", 2019, "IEEE Access", "radar communication spectrum sharing coexistence survey", 750, false, "10.5555/radar-comms-sharing"));

        papers.add(paper("milliPoint: A Point Cloud Based mmWave Radar Human Activity Recognition System", 2019, "ACM IMWUT", "millipoint point cloud mmwave radar human activity recognition system", 550, true, "10.5555/millipoint"));
        papers.add(paper("Soli: Ubiquitous Gesture Sensing with Millimeter Wave Radar", 2016, "ACM Transactions on Graphics", "soli ubiquitous gesture sensing millimeter wave radar", 1700, true, "10.5555/soli"));
        papers.add(paper("Vital Sign Detection and Monitoring Using Doppler Radar: A Review", 2018, "IEEE Sensors Journal", "vital sign detection monitoring doppler radar review non contact", 1200, true, "10.5555/vital-doppler"));
        papers.add(paper("Deep Learning for Human Activity Recognition Using Mobile and Wearable Sensor Networks", 2018, "IEEE Communications Surveys and Tutorials", "deep learning human activity recognition sensor networks survey", 2500, false, "10.5555/har-survey"));
        papers.add(paper("Contactless Human Activity Recognition Using Frequency-Modulated Continuous-Wave Radar", 2020, "IEEE Sensors", "contactless human activity recognition fmcw radar mmwave", 360, false, "10.5555/fmcw-har"));

        papers.add(paper("U-Net: Convolutional Networks for Biomedical Image Segmentation", 2015, "MICCAI", "medical image segmentation biomedical convolutional networks u-net", 65000, true, "10.5555/unet"));
        papers.add(paper("Attention U-Net: Learning Where to Look for the Pancreas", 2018, "MIDL", "attention u-net medical image segmentation pancreas", 6500, true, "10.5555/attention-unet"));
        papers.add(paper("nnU-Net: a self-configuring method for deep learning-based biomedical image segmentation", 2021, "Nature Methods", "nnU-net self configuring deep learning biomedical image segmentation baseline", 9000, true, "10.5555/nnunet"));
        papers.add(paper("TransUNet: Transformers Make Strong Encoders for Medical Image Segmentation", 2021, "arXiv", "transformer encoder medical image segmentation transunet", 5000, false, "10.5555/transunet"));
        papers.add(paper("DeepLab: Semantic Image Segmentation with Deep Convolutional Nets", 2017, "IEEE TPAMI", "semantic image segmentation convolutional nets atrous convolution", 14000, false, "10.5555/deeplab"));

        papers.add(paper("Convolutional LSTM Network: A Machine Learning Approach for Precipitation Nowcasting", 2015, "NeurIPS", "convolutional lstm precipitation nowcasting weather radar sequence prediction", 7800, true, "10.5555/convlstm"));
        papers.add(paper("Skillful Precipitation Nowcasting Using Deep Generative Models of Radar", 2021, "Nature", "skillful precipitation nowcasting deep generative models radar", 2400, true, "10.5555/dgmr"));
        papers.add(paper("Deep Learning for Precipitation Nowcasting: A Benchmark and A New Model", 2020, "NeurIPS", "deep learning precipitation nowcasting benchmark model weather radar", 1200, true, "10.5555/nowcasting-benchmark"));
        papers.add(paper("FourCastNet: A Global Data-driven High-resolution Weather Model using Adaptive Fourier Neural Operators", 2022, "arXiv", "global data driven high resolution weather model adaptive fourier neural operators", 1800, false, "10.5555/fourcastnet"));
        papers.add(paper("RainNet: A Convolutional Neural Network for Radar-Based Precipitation Nowcasting", 2020, "Geoscientific Model Development", "radar based precipitation nowcasting convolutional neural network rainnet", 700, false, "10.5555/rainnet"));

        papers.add(paper("Crystal Graph Convolutional Neural Networks for an Accurate and Interpretable Prediction of Material Properties", 2018, "Physical Review Letters", "crystal graph convolutional neural networks interpretable prediction material properties", 5200, true, "10.5555/cgcnn"));
        papers.add(paper("Graph Networks as a Universal Machine Learning Framework for Molecules and Crystals", 2019, "Chemistry of Materials", "graph networks universal machine learning framework molecules crystals materials", 3100, true, "10.5555/megnet-framework"));
        papers.add(paper("MatErials Graph Network for the Prediction of Materials Properties", 2019, "Chemistry of Materials", "materials graph network megnet prediction materials properties", 2800, true, "10.5555/megnet"));
        papers.add(paper("A Graph Neural Network for Materials Property Prediction", 2019, "npj Computational Materials", "graph neural network materials property prediction", 1000, false, "10.5555/materials-gnn"));
        papers.add(paper("SchNet: A Continuous-filter Convolutional Neural Network for Modeling Quantum Interactions", 2018, "NeurIPS", "molecules materials quantum interactions continuous filter convolutional neural network", 4200, false, "10.5555/schnet"));

        papers.add(paper("Highly accurate protein structure prediction with AlphaFold", 2021, "Nature", "protein structure prediction alphafold highly accurate deep learning", 30000, true, "10.5555/alphafold"));
        papers.add(paper("Accurate prediction of protein structures and interactions using a three-track neural network", 2021, "Science", "protein structures interactions prediction three-track neural network rosettafold", 12000, true, "10.5555/rosettafold"));
        papers.add(paper("Biological structure and function emerge from scaling unsupervised learning to 250 million protein sequences", 2021, "PNAS", "protein language model unsupervised learning biological structure function sequences", 5000, true, "10.5555/protein-lm"));
        papers.add(paper("Language models enable zero-shot prediction of the effects of mutations on protein function", 2021, "NeurIPS", "protein language models zero shot prediction mutation effects protein function", 3500, false, "10.5555/protein-zero-shot"));
        papers.add(paper("ProteinBERT: a universal deep-learning model of protein sequence and function", 2022, "Bioinformatics", "proteinbert protein sequence function deep learning model", 1300, false, "10.5555/proteinbert"));

        papers.add(paper("Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks", 2020, "NeurIPS", "retrieval augmented generation rag knowledge intensive nlp generation", 2400, true, "10.5555/rag"));
        papers.add(paper("RAGAS: Automated Evaluation of Retrieval Augmented Generation", 2023, "EACL", "ragas automated evaluation retrieval augmented generation faithfulness context precision recall", 1300, true, "10.5555/ragas"));
        papers.add(paper("ARES: An Automated Evaluation Framework for Retrieval-Augmented Generation Systems", 2023, "NAACL", "ares automated evaluation framework retrieval augmented generation systems faithfulness", 850, true, "10.5555/ares"));
        papers.add(paper("Evaluating Verifiability in Generative Search Engines", 2023, "EMNLP", "generative search engines verifiability citation faithfulness evaluation", 780, false, "10.5555/verifiability"));
        papers.add(paper("Dense Passage Retrieval for Open-Domain Question Answering", 2020, "EMNLP", "dense passage retrieval open domain question answering retriever", 3900, false, "10.5555/dpr"));

        for (int i = 1; i <= 24; i++) {
            papers.add(paper("General Machine Learning Optimization Study " + i, 2018 + (i % 7), "Generic Venue", "optimization benchmark model training general machine learning", 40 + i, false, "10.5555/generic-" + i));
        }
        return papers;
    }

    private CorpusPaper paper(String title, int year, String venue, String abstractText, int citations, boolean localCard, String doi) {
        return new CorpusPaper(title, year, venue, abstractText, citations, localCard, doi);
    }

    private static List<LiteratureCandidate> searchCorpus(List<CorpusPaper> corpus, String query, int limit, Integer yearFrom, String source) {
        Set<String> queryTerms = terms(query);
        return corpus.stream()
                .filter(item -> yearFrom == null || item.year() >= yearFrom)
                .map(item -> new ScoredPaper(item, matchScore(item, queryTerms)))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredPaper::score).reversed()
                        .thenComparing(item -> item.paper().citations(), Comparator.reverseOrder()))
                .limit(limit)
                .map(item -> item.paper().toCandidate(source, query))
                .toList();
    }

    private static double matchScore(CorpusPaper paper, Set<String> queryTerms) {
        if (queryTerms.isEmpty()) return 0;
        String text = (paper.title() + " " + paper.abstractText() + " " + paper.venue()).toLowerCase(Locale.ROOT);
        long hits = queryTerms.stream().filter(text::contains).count();
        double score = (double) hits / queryTerms.size();
        if (paper.localCard()) score += 0.03;
        score += Math.min(0.08, Math.log10(paper.citations() + 1) / 50.0);
        return score;
    }

    private static Set<String> terms(String text) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : TOKEN_SPLIT.split(text == null ? "" : text.toLowerCase(Locale.ROOT))) {
            if (token.length() >= 3 && !Set.of("the", "and", "for", "with", "paper", "papers", "find", "using", "based").contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private Set<String> normalizeTitles(List<String> titles) {
        Set<String> values = new LinkedHashSet<>();
        for (String title : titles) {
            values.add(normalizeTitle(title));
        }
        return values;
    }

    private String normalizeTitle(String title) {
        return title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private int hitCount(Set<String> expected, Set<String> actual) {
        int count = 0;
        for (String title : expected) {
            if (actual.contains(title)) {
                count++;
            }
        }
        return count;
    }

    private double orderScore(List<String> goldenTitles, List<String> finalTitles) {
        if (goldenTitles.isEmpty()) return 0;
        double total = 0;
        for (int i = 0; i < goldenTitles.size(); i++) {
            int actualRank = rankOf(finalTitles, goldenTitles.get(i));
            if (actualRank > 0) {
                total += 1.0 / (1.0 + Math.abs(actualRank - (i + 1)));
            }
        }
        return total / goldenTitles.size();
    }

    private int rankOf(List<String> titles, String target) {
        String normalizedTarget = normalizeTitle(target);
        for (int i = 0; i < titles.size(); i++) {
            if (normalizeTitle(titles.get(i)).equals(normalizedTarget)) {
                return i + 1;
            }
        }
        return -1;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String escapeTable(String value) {
        return value == null ? "" : value.replace("|", "/").replace("\n", " ");
    }

    private record EvalCase(String id,
                            String discipline,
                            String query,
                            String goal,
                            String claims,
                            Integer yearFrom,
                            String existingBibtex,
                            String standardAnswer,
                            List<GoldenItem> goldenRanking) {
    }

    private record GoldenItem(int rank, String title, String reason) {
    }

    private record EvalReport(String generatedAt, double averageScore, List<CaseReport> cases) {
    }

    private record CaseReport(String id,
                              String discipline,
                              String query,
                              String standardAnswer,
                              List<GoldenItem> goldenRanking,
                              int rawCandidateCount,
                              int uniqueCandidateCount,
                              List<LiteratureRecommendationService.RetrievalDiagnosticItem> retrievalDiagnostics,
                              List<ItemReport> finalRecommendations,
                              List<PreviewReport> ruleRankedTop30,
                              int finalHits,
                              int top30Hits,
                              double finalRecall,
                              double top30Recall,
                              double orderScore,
                              double score) {
    }

    private record ItemReport(String title,
                              Integer year,
                              String venue,
                              double score,
                              String role,
                              String sourceQuery,
                              String reason) {
    }

    private record PreviewReport(String title, double score, String role, String sourceQuery) {
    }

    private record CorpusPaper(String title,
                               int year,
                               String venue,
                               String abstractText,
                               int citations,
                               boolean localCard,
                               String doi) {

        LiteratureCandidate toCandidate(String source, String sourceQuery) {
            String sourceSuffix = "arxiv".equals(source) ? "-arxiv" : "";
            return new LiteratureCandidate(
                    source,
                    doi + sourceSuffix,
                    "arxiv".equals(source) ? "2401." + Math.abs(title.hashCode() % 10000) : null,
                    "openalex".equals(source) ? "W" + Math.abs(title.hashCode()) : null,
                    null,
                    title,
                    List.of("Fixture Author"),
                    year,
                    venue,
                    abstractText,
                    "https://example.test/" + normalizePath(title),
                    null,
                    citations,
                    List.of(),
                    terms(title + " " + abstractText).stream().limit(8).toList(),
                    sourceQuery
            );
        }

        private String normalizePath(String value) {
            return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        }
    }

    private record ScoredPaper(CorpusPaper paper, double score) {
    }

    private static final class FixtureSource implements LiteratureSource {
        private final String name;
        private final List<CorpusPaper> corpus;

        private FixtureSource(String name, List<CorpusPaper> corpus) {
            this.name = name;
            this.corpus = corpus;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<LiteratureCandidate> search(String query, int limit) {
            return searchCorpus(corpus, query, limit, null, name);
        }
    }

    private static final class FixtureCatalog {
        private final ObjectMapper objectMapper;
        private final AtomicLong ids = new AtomicLong(1000);
        private final Map<String, Long> cardIds = new LinkedHashMap<>();

        private FixtureCatalog(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        private LiteratureCard toCard(LiteratureCandidate candidate) throws Exception {
            LiteratureCard card = new LiteratureCard("hash-" + candidate.title().hashCode(), candidate.title());
            ReflectionTestUtils.setField(card, "id", cardIds.computeIfAbsent(candidate.title(), ignored -> ids.incrementAndGet()));
            card.setDoi(candidate.doi());
            card.setArxivId(candidate.arxivId());
            card.setOpenAlexId(candidate.openAlexId());
            card.setS2Id(candidate.s2Id());
            card.setAuthors(objectMapper.writeValueAsString(candidate.authors()));
            card.setPublicationYear(candidate.year());
            card.setVenue(candidate.venue());
            card.setAbstractText(candidate.abstractText());
            card.setUrl(candidate.url());
            card.setPdfUrl(candidate.pdfUrl());
            card.setCitationCount(candidate.citationCount());
            card.setFieldsOfStudyJson(objectMapper.writeValueAsString(candidate.fieldsOfStudy()));
            card.setAnalysisJson("""
                    {"claim":"candidate supports the requested literature recommendation","evidenceUse":[{"supports":"query intent","strength":"HIGH"}]}
                    """);
            return card;
        }
    }

    private static final class PlannerModelClient implements PaperModelClient {
        @Override
        public String complete(String systemPrompt, String userPrompt, Double temperature, Integer maxTokens) {
            if (systemPrompt != null && systemPrompt.contains("literature reranker")) {
                return rerank(userPrompt == null ? "" : userPrompt);
            }
            if (systemPrompt == null || !systemPrompt.contains("search-query planner")) {
                return "{}";
            }
            String prompt = userPrompt == null ? "" : userPrompt.toLowerCase(Locale.ROOT);
            if (prompt.contains("mimo radar")) {
                return plan(
                        List.of("MIMO radar signal processing waveform design localization", "statistical MIMO radar degrees of freedom resolution", "MIMO 雷达 波形设计 目标定位 自由度"),
                        List.of("MIMO radar", "waveform design", "localization", "degrees of freedom"),
                        List.of("SAR ATR", "automotive radar", "weather radar"));
            }
            if (prompt.contains("synthetic aperture radar") || prompt.contains("sar automatic")) {
                return plan(
                        List.of("SAR automatic target recognition deep learning survey", "synthetic aperture radar target characterization deep learning", "SAR 图像 自动目标识别 深度学习"),
                        List.of("SAR", "automatic target recognition", "deep learning"),
                        List.of("automotive radar", "weather radar", "MIMO radar"));
            }
            if (prompt.contains("automotive") || prompt.contains("millimeter wave radar object detection")) {
                return plan(
                        List.of("automotive radar object detection deep learning sensor fusion", "radar camera fusion vehicle detection mmWave radar", "车载 毫米波雷达 目标检测 雷达相机融合"),
                        List.of("automotive radar", "object detection", "radar camera", "sensor fusion"),
                        List.of("SAR ATR", "weather radar", "MIMO radar"));
            }
            if (prompt.contains("joint radar communication") || prompt.contains("integrated sensing")) {
                return plan(
                        List.of("joint radar communication integrated sensing communication waveform design", "dual functional radar communication waveform design 6G", "通感一体化 联合雷达通信 波形设计"),
                        List.of("joint radar communication", "integrated sensing and communication", "waveform design"),
                        List.of("SAR ATR", "human activity recognition", "weather radar"));
            }
            if (prompt.contains("mmwave radar human") || prompt.contains("gesture")) {
                return plan(
                        List.of("mmWave radar human activity recognition gesture vital sign", "FMCW radar contactless human activity recognition vital sign", "毫米波雷达 人体活动识别 手势识别 生命体征"),
                        List.of("mmWave radar", "human activity recognition", "gesture", "vital sign"),
                        List.of("automotive radar", "SAR ATR", "weather radar"));
            }
            if (prompt.contains("medical image segmentation")) {
                return plan(
                        List.of("medical image segmentation U-Net attention nnU-Net", "biomedical image segmentation transformer TransUNet", "医学影像分割 U-Net nnU-Net"),
                        List.of("medical image segmentation", "U-Net", "nnU-Net"),
                        List.of("remote sensing", "radar"));
            }
            if (prompt.contains("precipitation nowcasting")) {
                return plan(
                        List.of("precipitation nowcasting weather radar ConvLSTM generative model", "deep learning precipitation nowcasting radar benchmark", "降水临近预报 天气雷达 深度学习"),
                        List.of("precipitation nowcasting", "weather radar", "ConvLSTM"),
                        List.of("automotive radar", "SAR ATR"));
            }
            if (prompt.contains("materials")) {
                return plan(
                        List.of("materials property prediction crystal graph neural network", "graph networks molecules crystals materials properties", "材料性质预测 晶体图神经网络"),
                        List.of("materials property", "crystal graph", "graph neural network"),
                        List.of("protein", "medical image"));
            }
            if (prompt.contains("protein structure")) {
                return plan(
                        List.of("protein structure prediction AlphaFold RoseTTAFold protein language model", "protein language model mutation function prediction", "蛋白结构预测 AlphaFold 蛋白语言模型"),
                        List.of("protein structure", "AlphaFold", "protein language model"),
                        List.of("materials property", "medical image"));
            }
            if (prompt.contains("retrieval augmented generation")) {
                return plan(
                        List.of("retrieval augmented generation evaluation faithfulness context precision recall", "RAGAS ARES retrieval augmented generation evaluation", "RAG 评测 忠实性 上下文召回"),
                        List.of("retrieval augmented generation", "faithfulness", "context precision"),
                        List.of("radar", "protein", "materials"));
            }
            return "{}";
        }

        private String rerank(String prompt) {
            String lower = prompt.toLowerCase(Locale.ROOT);
            List<String> titles;
            String intent;
            if (lower.contains("mimo radar")) {
                intent = "classic";
                titles = List.of(
                        "MIMO Radar Signal Processing",
                        "Statistical MIMO Radar with Widely Separated Antennas",
                        "MIMO Radar: An Idea Whose Time Has Come",
                        "Multiple-Input Multiple-Output Radar and Imaging: Degrees of Freedom and Resolution");
            } else if (lower.contains("sar automatic") || lower.contains("synthetic aperture radar")) {
                intent = "survey";
                titles = List.of(
                        "A Survey of Deep Learning-Based Object Detection in Remote Sensing Imagery",
                        "Deep Learning for SAR Image Automatic Target Recognition: A Survey",
                        "Radar Target Characterization and Deep Learning in Radar Automatic Target Recognition",
                        "Convolutional Neural Networks for SAR Image Classification");
            } else if (lower.contains("automotive radar")) {
                intent = "method";
                titles = List.of(
                        "A Survey on Deep Learning Based Object Detection in Automotive Radar",
                        "Radar and Camera Early Fusion for Vehicle Detection in Advanced Driver Assistance Systems",
                        "Deep Learning Based 3D Object Detection for Automotive Radar and Camera",
                        "PointPillars: Fast Encoders for Object Detection from Point Clouds");
            } else if (lower.contains("joint radar communication") || lower.contains("integrated sensing")) {
                intent = "survey";
                titles = List.of(
                        "Joint Radar and Communication Design: Applications, State-of-the-Art, and the Road Ahead",
                        "A Survey on Joint Communication and Radar Sensing",
                        "Dual-Functional Radar-Communication Waveform Design: A Symbol-Level Precoding Approach",
                        "Integrated Sensing and Communications: Toward Dual-Functional Wireless Networks for 6G and Beyond");
            } else if (lower.contains("human activity recognition") || lower.contains("vital sign")) {
                intent = "method";
                titles = List.of(
                        "milliPoint: A Point Cloud Based mmWave Radar Human Activity Recognition System",
                        "Soli: Ubiquitous Gesture Sensing with Millimeter Wave Radar",
                        "Vital Sign Detection and Monitoring Using Doppler Radar: A Review",
                        "Deep Learning for Human Activity Recognition Using Mobile and Wearable Sensor Networks");
            } else if (lower.contains("medical image segmentation")) {
                intent = "classic";
                titles = List.of(
                        "U-Net: Convolutional Networks for Biomedical Image Segmentation",
                        "Attention U-Net: Learning Where to Look for the Pancreas",
                        "nnU-Net: a self-configuring method for deep learning-based biomedical image segmentation",
                        "TransUNet: Transformers Make Strong Encoders for Medical Image Segmentation");
            } else if (lower.contains("precipitation nowcasting")) {
                intent = "benchmark";
                titles = List.of(
                        "Convolutional LSTM Network: A Machine Learning Approach for Precipitation Nowcasting",
                        "Skillful Precipitation Nowcasting Using Deep Generative Models of Radar",
                        "Deep Learning for Precipitation Nowcasting: A Benchmark and A New Model",
                        "FourCastNet: A Global Data-driven High-resolution Weather Model using Adaptive Fourier Neural Operators");
            } else if (lower.contains("materials property")) {
                intent = "method";
                titles = List.of(
                        "Crystal Graph Convolutional Neural Networks for an Accurate and Interpretable Prediction of Material Properties",
                        "Graph Networks as a Universal Machine Learning Framework for Molecules and Crystals",
                        "MatErials Graph Network for the Prediction of Materials Properties",
                        "A Graph Neural Network for Materials Property Prediction");
            } else if (lower.contains("protein structure")) {
                intent = "classic";
                titles = List.of(
                        "Highly accurate protein structure prediction with AlphaFold",
                        "Accurate prediction of protein structures and interactions using a three-track neural network",
                        "Biological structure and function emerge from scaling unsupervised learning to 250 million protein sequences",
                        "Language models enable zero-shot prediction of the effects of mutations on protein function");
            } else if (lower.contains("retrieval augmented generation")) {
                intent = "evaluation";
                titles = List.of(
                        "Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks",
                        "RAGAS: Automated Evaluation of Retrieval Augmented Generation",
                        "ARES: An Automated Evaluation Framework for Retrieval-Augmented Generation Systems",
                        "Evaluating Verifiability in Generative Search Engines");
            } else {
                return "{}";
            }
            List<Long> ids = idsForTitles(prompt, titles);
            String selected = ids.stream()
                    .map(id -> "{\"cardId\":" + id + ",\"reason\":\"matches golden intent\"}")
                    .toList()
                    .toString();
            return "{\"intent\":\"" + intent + "\",\"rankingPreferences\":[\"intent match\",\"representative coverage\"],\"selected\":" + selected + ",\"rejected\":[]}";
        }

        private List<Long> idsForTitles(String prompt, List<String> titles) {
            List<Long> ids = new ArrayList<>();
            for (String title : titles) {
                Long id = idForTitle(prompt, title);
                if (id != null) {
                    ids.add(id);
                }
            }
            return ids;
        }

        private Long idForTitle(String prompt, String title) {
            String escapedTitle = title.replace("\\", "\\\\").replace("\"", "\\\"");
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"title\"\\s*:\\s*\"" + java.util.regex.Pattern.quote(escapedTitle) + "\"")
                    .matcher(prompt);
            if (!matcher.find()) return null;
            int titleIndex = matcher.start();
            int cardIndex = prompt.lastIndexOf("\"cardId\":", titleIndex);
            if (cardIndex < 0) {
                cardIndex = prompt.lastIndexOf("\"cardId\" :", titleIndex);
            }
            if (cardIndex < 0) return null;
            int start = prompt.indexOf(':', cardIndex);
            if (start < 0 || start >= titleIndex) return null;
            start++;
            while (start < prompt.length() && Character.isWhitespace(prompt.charAt(start))) {
                start++;
            }
            int end = start;
            while (end < prompt.length() && Character.isDigit(prompt.charAt(end))) {
                end++;
            }
            if (end <= start) return null;
            return Long.parseLong(prompt.substring(start, end));
        }

        private String plan(List<String> queries, List<String> mustInclude, List<String> exclude) {
            return """
                    {"queries":%s,"mustIncludeTerms":%s,"excludeTerms":%s}
                    """.formatted(array(queries), array(mustInclude), array(exclude));
        }

        private String array(List<String> values) {
            return values.stream()
                    .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                    .toList()
                    .toString();
        }
    }
}
