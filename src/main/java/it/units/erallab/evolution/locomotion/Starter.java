/*
 * Copyright 2020 Eric Medvet <eric.medvet@gmail.com> (as eric)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.units.erallab.evolution.locomotion;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import it.units.erallab.evolution.builder.DirectNumbersGrid;
import it.units.erallab.evolution.builder.FunctionGrid;
import it.units.erallab.evolution.builder.FunctionNumbersGrid;
import it.units.erallab.evolution.builder.PrototypedFunctionBuilder;
import it.units.erallab.evolution.builder.evolver.*;
import it.units.erallab.evolution.builder.phenotype.FGraph;
import it.units.erallab.evolution.builder.phenotype.MLP;
import it.units.erallab.evolution.builder.phenotype.PruningMLP;
import it.units.erallab.evolution.builder.robot.*;
import it.units.erallab.hmsrobots.core.controllers.Controller;
import it.units.erallab.hmsrobots.core.controllers.MultiLayerPerceptron;
import it.units.erallab.hmsrobots.core.controllers.PruningMultiLayerPerceptron;
import it.units.erallab.hmsrobots.core.objects.Robot;
import it.units.erallab.hmsrobots.core.objects.Voxel;
import it.units.erallab.hmsrobots.tasks.locomotion.Locomotion;
import it.units.erallab.hmsrobots.tasks.locomotion.Outcome;
import it.units.erallab.hmsrobots.util.DoubleRange;
import it.units.erallab.hmsrobots.util.Grid;
import it.units.erallab.hmsrobots.util.RobotUtils;
import it.units.malelab.jgea.Worker;
import it.units.malelab.jgea.core.evolver.Evolver;
import it.units.malelab.jgea.core.evolver.stopcondition.FitnessEvaluations;
import it.units.malelab.jgea.core.listener.*;
import it.units.malelab.jgea.core.listener.telegram.TelegramProgressMonitor;
import it.units.malelab.jgea.core.listener.telegram.TelegramUpdater;
import it.units.malelab.jgea.core.order.PartialComparator;
import it.units.malelab.jgea.core.util.Misc;
import it.units.malelab.jgea.core.util.Pair;
import it.units.malelab.jgea.core.util.SequentialFunction;
import org.dyn4j.dynamics.Settings;

import java.io.File;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static it.units.erallab.hmsrobots.util.Utils.params;
import static it.units.malelab.jgea.core.listener.NamedFunctions.*;
import static it.units.malelab.jgea.core.util.Args.*;

/**
 * @author eric
 */
public class Starter extends Worker {

  public final static Settings PHYSICS_SETTINGS = new Settings();

  public record ValidationOutcome(
      Evolver.Event<?, ? extends Robot, ? extends Outcome> event,
      Map<String, Object> keys,
      Outcome outcome) {
  }

  public static final int CACHE_SIZE = 1000;
  public static final String MAPPER_PIPE_CHAR = "<";
  public static final String SEQUENCE_SEPARATOR_CHAR = ">";
  public static final String SEQUENCE_ITERATION_CHAR = ":";

  public Starter(String[] args) {
    super(args);
  }

  public static void main(String[] args) {
    new Starter(args);
  }

  @Override
  public void run() {
    int spectrumSize = 8;
    double spectrumMinFreq = 0d;
    double spectrumMaxFreq = 4d;
    double episodeTime = d(a("episodeTime", "10"));
    double episodeTransientTime = d(a("episodeTransientTime", "1"));
    double validationEpisodeTime = d(a("validationEpisodeTime", Double.toString(episodeTime)));
    double validationEpisodeTransientTime = d(a("validationEpisodeTransientTime", Double.toString(episodeTransientTime)));
    double videoEpisodeTime = d(a("videoEpisodeTime", "10"));
    double videoEpisodeTransientTime = d(a("videoEpisodeTransientTime", "0"));
    int nEvals = i(a("nEvals", "500"));
    int[] seeds = ri(a("seed", "0:1"));
    String experimentName = a("expName", "short");
    List<String> terrainNames = l(a("terrain", "flat"));
    List<String> targetShapeNames = l(a("shape", "biped-4x3"));
    List<String> targetSensorConfigNames = l(a("sensorConfig", "spinedTouch-f-f-0.01"));
    List<String> transformationNames = l(a("transformation", "identity"));
    List<String> evolverNames = l(a("evolver", "ES-36-0.35-f"));
    List<String> mapperNames = l(a("mapper", "fixedCentralized<MLP-1-1-tanh"));
    String lastFileName = a("lastFile", null);
    String bestFileName = a("bestFile", null);
    String allFileName = a("allFile", null);
    String finalFileName = a("finalFile", null);
    String validationFileName = a("validationFile", null);
    boolean deferred = a("deferred", "true").startsWith("t");
    String telegramBotId = a("telegramBotId", null);
    long telegramChatId = Long.parseLong(a("telegramChatId", "0"));
    List<String> serializationFlags = l(a("serialization", "")); //last,best,all,final
    boolean output = a("output", "false").startsWith("t");
    boolean detailedOutput = a("detailedOutput", "false").startsWith("t");
    boolean cacheOutcome = a("cache", "false").startsWith("t");
    List<String> validationTransformationNames = l(a("validationTransformation", "")).stream().filter(s -> !s.isEmpty()).collect(Collectors.toList());
    List<String> validationTerrainNames = l(a("validationTerrain", "flat,downhill-30")).stream().filter(s -> !s.isEmpty()).collect(Collectors.toList());
    Function<Outcome, Double> fitnessFunction = Outcome::getVelocity;

    List<String> springF = l(a("springF", ""));
    List<String> springD = l(a("springD", ""));
    List<String> friction = l(a("friction", ""));
    List<String> activeDelta = l(a("activeDelta", ""));
    List<String> propertyNames = List.of("springF", "springD", "friction", "activeDelta");
    List<List<String>> propertyValues = List.of(springF, springD, friction, activeDelta);
    List<Map<String, Double>> voxelPropertiesCombinations = buildVoxelPropertiesCombinations(propertyNames, propertyValues);

    //consumers
    List<NamedFunction<Evolver.Event<?, ? extends Robot, ? extends Outcome>, ?>> keysFunctions = NamedFunctions.keysFunctions();
    List<NamedFunction<Evolver.Event<?, ? extends Robot, ? extends Outcome>, ?>> basicFunctions = NamedFunctions.basicFunctions();
    List<NamedFunction<Evolver.Individual<?, ? extends Robot, ? extends Outcome>, ?>> basicIndividualFunctions = NamedFunctions.individualFunctions(fitnessFunction);
    List<NamedFunction<Evolver.Event<?, ? extends Robot, ? extends Outcome>, ?>> populationFunctions = NamedFunctions.populationFunctions(fitnessFunction);
    List<NamedFunction<Evolver.Event<?, ? extends Robot, ? extends Outcome>, ?>> visualFunctions = Misc.concat(List.of(
        NamedFunctions.visualPopulationFunctions(fitnessFunction),
        detailedOutput ? NamedFunction.then(best(), NamedFunctions.visualIndividualFunctions()) : List.of()
    ));
    List<NamedFunction<Outcome, ?>> basicOutcomeFunctions = NamedFunctions.basicOutcomeFunctions();
    List<NamedFunction<Outcome, ?>> detailedOutcomeFunctions = NamedFunctions.detailedOutcomeFunctions(spectrumMinFreq, spectrumMaxFreq, spectrumSize);
    List<NamedFunction<Outcome, ?>> visualOutcomeFunctions = detailedOutput ? NamedFunctions.visualOutcomeFunctions(spectrumMinFreq, spectrumMaxFreq) : List.of();
    Listener.Factory<Evolver.Event<?, ? extends Robot, ? extends Outcome>> factory = Listener.Factory.deaf();
    ProgressMonitor progressMonitor = new ScreenProgressMonitor(System.out);
    //screen listener
    if (bestFileName == null || output) {
      factory = factory.and(new TabularPrinter<>(Misc.concat(List.of(
          basicFunctions,
          populationFunctions,
          visualFunctions,
          NamedFunction.then(best(), basicIndividualFunctions),
          NamedFunction.then(as(Outcome.class).of(fitness()).of(best()), basicOutcomeFunctions),
          NamedFunction.then(as(Outcome.class).of(fitness()).of(best()), visualOutcomeFunctions)
      ))));
    }
    //file listeners
    if (lastFileName != null) {
      factory = factory.and(new CSVPrinter<>(Misc.concat(List.of(
          keysFunctions,
          basicFunctions,
          populationFunctions,
          NamedFunction.then(best(), basicIndividualFunctions),
          NamedFunction.then(as(Outcome.class).of(fitness()).of(best()), basicOutcomeFunctions),
          NamedFunction.then(as(Outcome.class).of(fitness()).of(best()), detailedOutcomeFunctions),
          NamedFunction.then(best(), NamedFunctions.serializationFunction(serializationFlags.contains("last")))
      )), new File(lastFileName)
      ).onLast());
    }
    if (bestFileName != null) {
      factory = factory.and(new CSVPrinter<>(Misc.concat(List.of(
          keysFunctions,
          basicFunctions,
          populationFunctions,
          NamedFunction.then(best(), basicIndividualFunctions),
          NamedFunction.then(as(Outcome.class).of(fitness()).of(best()), basicOutcomeFunctions),
          NamedFunction.then(as(Outcome.class).of(fitness()).of(best()), detailedOutcomeFunctions),
          NamedFunction.then(best(), NamedFunctions.serializationFunction(serializationFlags.contains("best")))
      )), new File(bestFileName)
      ));
    }
    if (allFileName != null) {
      factory = factory.and(Listener.Factory.forEach(
          event -> event.orderedPopulation().all().stream()
              .map(i -> Pair.of(event, i))
              .collect(Collectors.toList()),
          new CSVPrinter<>(
              Misc.concat(List.of(
                  NamedFunction.then(f("event", Pair::first), keysFunctions),
                  NamedFunction.then(f("event", Pair::first), basicFunctions),
                  NamedFunction.then(f("individual", Pair::second), basicIndividualFunctions),
                  NamedFunction.then(f("individual", Pair::second), NamedFunctions.serializationFunction(serializationFlags.contains("all")))
              )),
              new File(allFileName)
          )
      ));
    }
    if (finalFileName != null) {
      Listener.Factory<Evolver.Event<?, ? extends Robot, ? extends Outcome>> entirePopulationFactory = Listener.Factory.forEach(
          event -> event.orderedPopulation().all().stream()
              .map(i -> Pair.of(event, i))
              .collect(Collectors.toList()),
          new CSVPrinter<>(
              Misc.concat(List.of(
                  NamedFunction.then(f("event", Pair::first), keysFunctions),
                  NamedFunction.then(f("event", Pair::first), basicFunctions),
                  NamedFunction.then(f("individual", Pair::second), basicIndividualFunctions),
                  NamedFunction.then(f("individual", Pair::second), NamedFunctions.serializationFunction(serializationFlags.contains("final"))))),
              new File(finalFileName)
          )
      );
      factory = factory.and(entirePopulationFactory.onLast());
    }
    //validation listener
    if (validationFileName != null) {
      if (!validationTerrainNames.isEmpty() && validationTransformationNames.isEmpty()) {
        validationTransformationNames.add("identity");
      }
      if (validationTerrainNames.isEmpty() && !validationTransformationNames.isEmpty()) {
        validationTerrainNames.add(terrainNames.get(0));
      }
      Listener.Factory<Evolver.Event<?, ? extends Robot, ? extends Outcome>> validationFactory = Listener.Factory.forEach(
          NamedFunctions.validation(validationTerrainNames, validationTransformationNames, List.of(0), validationEpisodeTime),
          new CSVPrinter<>(
              Misc.concat(List.of(
                  NamedFunction.then(f("event", (ValidationOutcome vo) -> vo.event), basicFunctions),
                  NamedFunction.then(f("event", (ValidationOutcome vo) -> vo.event), keysFunctions),
                  NamedFunction.then(f("keys", (ValidationOutcome vo) -> vo.keys), List.of(
                      f("validation.terrain", (Map<String, Object> map) -> map.get("validation.terrain")),
                      f("validation.transformation", (Map<String, Object> map) -> map.get("validation.transformation")),
                      f("validation.seed", "%2d", (Map<String, Object> map) -> map.get("validation.seed")),
                      f("validation.episode.time", (Map<String, Object> map) -> map.get("validation.episode.time")),
                      f("validation.transient.time", (Map<String, Object> map) -> validationEpisodeTransientTime)
                  )),
                  NamedFunction.then(
                      f("outcome", (ValidationOutcome vo) -> vo.outcome.subOutcome(validationEpisodeTransientTime, validationEpisodeTime)),
                      basicOutcomeFunctions
                  ),
                  NamedFunction.then(
                      f("outcome", (ValidationOutcome vo) -> vo.outcome.subOutcome(validationEpisodeTransientTime, validationEpisodeTime)),
                      detailedOutcomeFunctions
                  )
              )),
              new File(validationFileName)
          )
      ).onLast();
      factory = factory.and(validationFactory);
    }
    //telegram listener
    if (telegramBotId != null && telegramChatId != 0) {
      factory = factory.and(new TelegramUpdater<>(List.of(
          NamedFunctions.lastEventToString(fitnessFunction),
          NamedFunctions.fitnessPlot(fitnessFunction),
          NamedFunctions.centerPositionPlot(),
          NamedFunctions.bestVideo(videoEpisodeTransientTime, videoEpisodeTime, PHYSICS_SETTINGS)
      ), telegramBotId, telegramChatId));
      progressMonitor = progressMonitor.and(new TelegramProgressMonitor(telegramBotId, telegramChatId));
    }
    //summarize params
    L.info("Experiment name: " + experimentName);
    L.info("Evolvers: " + evolverNames);
    L.info("Mappers: " + mapperNames);
    L.info("Shapes: " + targetShapeNames);
    L.info("Sensor configs: " + targetSensorConfigNames);
    L.info("Terrains: " + terrainNames);
    L.info("Transformations: " + transformationNames);
    L.info("Validations: " + Lists.cartesianProduct(validationTerrainNames, validationTransformationNames));
    //start iterations
    int nOfRuns = seeds.length * terrainNames.size() * targetShapeNames.size() * targetSensorConfigNames.size() * mapperNames.size() * transformationNames.size() * evolverNames.size() * voxelPropertiesCombinations.size();
    int counter = 0;
    for (int seed : seeds) {
      for (String terrainName : terrainNames) {
        for (String targetShapeName : targetShapeNames) {
          for (String targetSensorConfigName : targetSensorConfigNames) {
            for (String mapperName : mapperNames) {
              for (String transformationName : transformationNames) {
                for (String evolverName : evolverNames) {
                  for (Map<String, Double> voxelProperties : voxelPropertiesCombinations) {
                    counter = counter + 1;
                    final Random random = new Random(seed);
                    //prepare keys
                    Map<String, Object> keys = Map.ofEntries(
                        Map.entry("experiment.name", experimentName),
                        Map.entry("seed", seed),
                        Map.entry("terrain", terrainName),
                        Map.entry("shape", targetShapeName),
                        Map.entry("sensor.config", targetSensorConfigName),
                        Map.entry("mapper", mapperName),
                        Map.entry("transformation", transformationName),
                        Map.entry("evolver", evolverName),
                        Map.entry("episode.time", episodeTime),
                        Map.entry("episode.transient.time", episodeTransientTime)
                    );
                    //prepare target
                    Robot target = new Robot(
                        Controller.empty(),
                        changeRobotsPhysicalProperties(
                            RobotUtils.buildSensorizingFunction(targetSensorConfigName).apply(RobotUtils.buildShape(targetShapeName)),
                            voxelProperties
                        )
                    );
                    //build evolver
                    Evolver<?, Robot, Outcome> evolver;
                    try {
                      evolver = buildEvolver(evolverName, mapperName, target, fitnessFunction);
                    } catch (ClassCastException | IllegalArgumentException e) {
                      L.warning(String.format(
                          "Cannot instantiate %s for %s: %s",
                          evolverName,
                          mapperName,
                          e
                      ));
                      continue;
                    }
                    Listener<Evolver.Event<?, ? extends Robot, ? extends Outcome>> listener = Listener.all(List.of(
                        new EventAugmenter(keys),
                        factory.build()
                    ));
                    if (deferred) {
                      listener = listener.deferred(executorService);
                    }
                    //optimize
                    Stopwatch stopwatch = Stopwatch.createStarted();
                    progressMonitor.notify(((float) counter - 1) / nOfRuns, String.format("(%d/%d); Starting %s", counter, nOfRuns, keys));
                    try {
                      Collection<Robot> solutions = evolver.solve(
                          buildTaskFromName(transformationName, terrainName, episodeTime, random, cacheOutcome).andThen(o -> o.subOutcome(episodeTransientTime, episodeTime)),
                          new FitnessEvaluations(nEvals),
                          random,
                          executorService,
                          listener
                      );
                      progressMonitor.notify((float) counter / nOfRuns, String.format("(%d/%d); Done: %d solutions in %4ds", counter, nOfRuns, solutions.size(), stopwatch.elapsed(TimeUnit.SECONDS)));
                    } catch (Exception e) {
                      L.severe(String.format("Cannot complete %s due to %s",
                          keys,
                          e
                      ));
                      e.printStackTrace(); // TODO possibly to be removed
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
    factory.shutdown();
  }

  public static EvolverBuilder<?> getEvolverBuilderFromName(String name) {
    String numGA = "numGA-(?<nPop>\\d+)-(?<diversity>(t|f))-(?<remap>(t|f))";
    String intGA = "intGA-(?<nPop>\\d+)-(?<diversity>(t|f))-(?<remap>(t|f))";
    String numGASpeciated = "numGASpec-(?<nPop>\\d+)-(?<nSpecies>\\d+)-(?<criterion>(" + Arrays.stream(DoublesSpeciated.SpeciationCriterion.values()).map(c -> c.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining("|")) + "))-(?<remap>(t|f))";
    String cmaES = "CMAES";
    String eS = "ES-(?<nPop>\\d+)-(?<sigma>\\d+(\\.\\d+)?)-(?<remap>(t|f))";
    Map<String, String> params;
    if ((params = params(numGA, name)) != null) {
      return new DoublesStandard(
          Integer.parseInt(params.get("nPop")),
          (int) Math.max(Math.round((double) Integer.parseInt(params.get("nPop")) / 10d), 3),
          0.75d,
          params.get("diversity").equals("t"),
          params.get("remap").equals("t")
      );
    }
    if ((params = params(intGA, name)) != null) {
      return new IntegersStandard(
          Integer.parseInt(params.get("nPop")),
          (int) Math.max(Math.round((double) Integer.parseInt(params.get("nPop")) / 10d), 3),
          0.75d,
          params.get("diversity").equals("t"),
          params.get("remap").equals("t")
      );
    }
    if ((params = params(numGASpeciated, name)) != null) {
      return new DoublesSpeciated(
          Integer.parseInt(params.get("nPop")),
          Integer.parseInt(params.get("nSpecies")),
          0.75d,
          DoublesSpeciated.SpeciationCriterion.valueOf(params.get("criterion").toUpperCase()),
          params.get("remap").equals("t")
      );
    }
    if ((params = params(eS, name)) != null) {
      return new ES(
          Double.parseDouble(params.get("sigma")),
          Integer.parseInt(params.get("nPop")),
          params.get("remap").equals("t")
      );
    }
    //noinspection UnusedAssignment
    if ((params = params(cmaES, name)) != null) {
      return new CMAES();
    }
    throw new IllegalArgumentException(String.format("Unknown evolver builder name: %s", name));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static PrototypedFunctionBuilder<?, ?> getMapperBuilderFromName(String name) {
    String fixedCentralized = "fixedCentralized";
    String fixedHomoDistributed = "fixedHomoDist-(?<nSignals>\\d+)";
    String fixedHeteroDistributed = "fixedHeteroDist-(?<nSignals>\\d+)";
    String fixedPhasesFunction = "fixedPhasesFunct-(?<f>\\d+)";
    String fixedPhases = "fixedPhases-(?<f>\\d+(\\.\\d+)?)";
    String fixedAutoPoses = "fixedAutoPoses-(?<stepT>\\d+(\\.\\d+)?)-(?<nRegions>\\d+)-(?<nUniquePoses>\\d+)-(?<nPoses>\\d+)";
    String bodySin = "bodySin-(?<fullness>\\d+(\\.\\d+)?)-(?<minF>\\d+(\\.\\d+)?)-(?<maxF>\\d+(\\.\\d+)?)";
    String bodyAndHomoDistributed = "bodyAndHomoDist-(?<fullness>\\d+(\\.\\d+)?)-(?<nSignals>\\d+)-(?<nLayers>\\d+)";
    String sensorAndBodyAndHomoDistributed = "sensorAndBodyAndHomoDist-(?<fullness>\\d+(\\.\\d+)?)-(?<nSignals>\\d+)-(?<nLayers>\\d+)-(?<position>(t|f))";
    String sensorCentralized = "sensorCentralized-(?<nLayers>\\d+)";
    String mlp = "MLP-(?<ratio>\\d+(\\.\\d+)?)-(?<nLayers>\\d+)(-(?<actFun>(sin|tanh|sigmoid|relu)))?";
    String pruningMlp = "pMLP-(?<ratio>\\d+(\\.\\d+)?)-(?<nLayers>\\d+)-(?<actFun>(sin|tanh|sigmoid|relu))-(?<pruningTime>\\d+(\\.\\d+)?)-(?<pruningRate>0(\\.\\d+)?)-(?<criterion>(weight|abs_signal_mean|random))";
    String directNumGrid = "directNumGrid";
    String functionNumGrid = "functionNumGrid";
    String fgraph = "fGraph";
    String functionGrid = "fGrid-(?<innerMapper>.*)";
    Map<String, String> params;
    //robot mappers
    //noinspection UnusedAssignment
    if ((params = params(fixedCentralized, name)) != null) {
      return new FixedCentralized();
    }
    if ((params = params(fixedHomoDistributed, name)) != null) {
      return new FixedHomoDistributed(
          Integer.parseInt(params.get("nSignals"))
      );
    }
    if ((params = params(fixedHeteroDistributed, name)) != null) {
      return new FixedHeteroDistributed(
          Integer.parseInt(params.get("nSignals"))
      );
    }
    if ((params = params(fixedPhasesFunction, name)) != null) {
      return new FixedPhaseFunction(
          Double.parseDouble(params.get("f")),
          1d
      );
    }
    if ((params = params(fixedPhases, name)) != null) {
      return new FixedPhaseValues(
          Double.parseDouble(params.get("f")),
          1d
      );
    }
    if ((params = params(fixedAutoPoses, name)) != null) {
      return new FixedAutoPoses(
          Integer.parseInt(params.get("nUniquePoses")),
          Integer.parseInt(params.get("nPoses")),
          Integer.parseInt(params.get("nRegions")),
          16,
          Double.parseDouble(params.get("stepT"))
      );
    }
    if ((params = params(bodyAndHomoDistributed, name)) != null) {
      return new BodyAndHomoDistributed(
          Integer.parseInt(params.get("nSignals")),
          Double.parseDouble(params.get("fullness"))
      )
          .compose(PrototypedFunctionBuilder.of(List.of(
              new MLP(2d, 3, MultiLayerPerceptron.ActivationFunction.SIN),
              new MLP(0.65d, Integer.parseInt(params.get("nLayers")))
          )))
          .compose(PrototypedFunctionBuilder.merger());
    }
    if ((params = params(sensorAndBodyAndHomoDistributed, name)) != null) {
      return new SensorAndBodyAndHomoDistributed(
          Integer.parseInt(params.get("nSignals")),
          Double.parseDouble(params.get("fullness")),
          params.get("position").equals("t")
      )
          .compose(PrototypedFunctionBuilder.of(List.of(
              new MLP(2d, 3, MultiLayerPerceptron.ActivationFunction.SIN),
              new MLP(1.5d, Integer.parseInt(params.get("nLayers")))
          )))
          .compose(PrototypedFunctionBuilder.merger());
    }
    if ((params = params(bodySin, name)) != null) {
      return new BodyAndSinusoidal(
          Double.parseDouble(params.get("minF")),
          Double.parseDouble(params.get("maxF")),
          Double.parseDouble(params.get("fullness")),
          Set.of(BodyAndSinusoidal.Component.FREQUENCY, BodyAndSinusoidal.Component.PHASE, BodyAndSinusoidal.Component.AMPLITUDE)
      );
    }
    if ((params = params(fixedHomoDistributed, name)) != null) {
      return new FixedHomoDistributed(
          Integer.parseInt(params.get("nSignals"))
      );
    }
    if ((params = params(sensorCentralized, name)) != null) {
      return new SensorCentralized()
          .compose(PrototypedFunctionBuilder.of(List.of(
              new MLP(2d, 3, MultiLayerPerceptron.ActivationFunction.SIN),
              new MLP(1.5d, Integer.parseInt(params.get("nLayers")))
          )))
          .compose(PrototypedFunctionBuilder.merger());
    }
    //function mappers
    if ((params = params(mlp, name)) != null) {
      return new MLP(
          Double.parseDouble(params.get("ratio")),
          Integer.parseInt(params.get("nLayers")),
          params.containsKey("actFun") ? MultiLayerPerceptron.ActivationFunction.valueOf(params.get("actFun").toUpperCase()) : MultiLayerPerceptron.ActivationFunction.TANH
      );
    }
    if ((params = params(pruningMlp, name)) != null) {
      return new PruningMLP(
          Double.parseDouble(params.get("ratio")),
          Integer.parseInt(params.get("nLayers")),
          MultiLayerPerceptron.ActivationFunction.valueOf(params.get("actFun").toUpperCase()),
          Double.parseDouble(params.get("pruningTime")),
          Double.parseDouble(params.get("pruningRate")),
          PruningMultiLayerPerceptron.Context.NETWORK,
          PruningMultiLayerPerceptron.Criterion.valueOf(params.get("criterion").toUpperCase())

      );
    }
    //noinspection UnusedAssignment
    if ((params = params(fgraph, name)) != null) {
      return new FGraph();
    }
    //misc
    if ((params = params(functionGrid, name)) != null) {
      return new FunctionGrid((PrototypedFunctionBuilder) getMapperBuilderFromName(params.get("innerMapper")));
    }
    //noinspection UnusedAssignment
    if ((params = params(directNumGrid, name)) != null) {
      return new DirectNumbersGrid();
    }
    //noinspection UnusedAssignment
    if ((params = params(functionNumGrid, name)) != null) {
      return new FunctionNumbersGrid();
    }
    throw new IllegalArgumentException(String.format("Unknown mapper name: %s", name));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Evolver<?, Robot, Outcome> buildEvolver(String evolverName, String robotMapperName, Robot target, Function<Outcome, Double> outcomeMeasure) {
    PrototypedFunctionBuilder<?, ?> mapperBuilder = null;
    for (String piece : robotMapperName.split(MAPPER_PIPE_CHAR)) {
      if (mapperBuilder == null) {
        mapperBuilder = getMapperBuilderFromName(piece);
      } else {
        mapperBuilder = mapperBuilder.compose((PrototypedFunctionBuilder) getMapperBuilderFromName(piece));
      }
    }
    return getEvolverBuilderFromName(evolverName).build(
        (PrototypedFunctionBuilder) mapperBuilder,
        target,
        PartialComparator.from(Double.class).comparing(outcomeMeasure).reversed()
    );
  }

  private static Function<Robot, Outcome> buildTaskFromName(String transformationSequenceName, String terrainSequenceName, double episodeT, Random random, boolean cacheOutcome) {
    //for sequence, assume format '99:name>99:name'
    //transformations
    Function<Robot, Robot> transformation;
    if (transformationSequenceName.contains(SEQUENCE_SEPARATOR_CHAR)) {
      transformation = new SequentialFunction<>(getSequence(transformationSequenceName).entrySet().stream()
          .collect(Collectors.toMap(
                  Map.Entry::getKey,
                  e -> RobotUtils.buildRobotTransformation(e.getValue(), random)
              )
          ));
    } else {
      transformation = RobotUtils.buildRobotTransformation(transformationSequenceName, random);
    }
    //terrains
    Function<Robot, Outcome> task;
    if (terrainSequenceName.contains(SEQUENCE_SEPARATOR_CHAR)) {
      task = new SequentialFunction<>(getSequence(terrainSequenceName).entrySet().stream()
          .collect(Collectors.toMap(
                  Map.Entry::getKey,
                  e -> buildLocomotionTask(e.getValue(), episodeT, random, cacheOutcome)
              )
          ));
    } else {
      task = buildLocomotionTask(terrainSequenceName, episodeT, random, cacheOutcome);
    }
    return task.compose(transformation);
  }

  public static Function<Robot, Outcome> buildLocomotionTask(String terrainName, double episodeT, Random random, boolean cacheOutcome) {
    if (!terrainName.contains("-rnd") && cacheOutcome) {
      return Misc.cached(new Locomotion(
          episodeT,
          Locomotion.createTerrain(terrainName),
          PHYSICS_SETTINGS
      ), CACHE_SIZE);
    }
    return r -> new Locomotion(
        episodeT,
        Locomotion.createTerrain(terrainName.replace("-rnd", "-" + random.nextInt(10000))),
        PHYSICS_SETTINGS
    ).apply(r);
  }

  public static SortedMap<Long, String> getSequence(String sequenceName) {
    return new TreeMap<>(Arrays.stream(sequenceName.split(SEQUENCE_SEPARATOR_CHAR)).collect(Collectors.toMap(
        s -> s.contains(SEQUENCE_ITERATION_CHAR) ? Long.parseLong(s.split(SEQUENCE_ITERATION_CHAR)[0]) : 0,
        s -> s.contains(SEQUENCE_ITERATION_CHAR) ? s.split(SEQUENCE_ITERATION_CHAR)[1] : s
    )));
  }

  private static Grid<Voxel> changeRobotsPhysicalProperties(Grid<Voxel> originalBody, Map<String, Double> voxelProperties) {
    double springF = voxelProperties.getOrDefault("springF", Voxel.SPRING_F);
    double springD = voxelProperties.getOrDefault("springD", Voxel.SPRING_D);
    double friction = voxelProperties.getOrDefault("friction", Voxel.FRICTION);
    DoubleRange areaRatioActiveRange;
    if (voxelProperties.containsKey("activeDelta")) {
      double activeDelta = voxelProperties.get("activeDelta");
      areaRatioActiveRange = DoubleRange.of(1 - activeDelta, 1 + activeDelta);
    } else {
      areaRatioActiveRange = Voxel.AREA_RATIO_ACTIVE_RANGE;
    }
    return Grid.create(originalBody, v -> v == null ? null : new Voxel(
        Voxel.SIDE_LENGTH,
        Voxel.MASS_SIDE_LENGTH_RATIO,
        springF,
        springD,
        Voxel.MASS_LINEAR_DAMPING,
        Voxel.MASS_ANGULAR_DAMPING,
        friction,
        Voxel.RESTITUTION,
        Voxel.MASS,
        Voxel.AREA_RATIO_PASSIVE_RANGE,
        areaRatioActiveRange,
        Voxel.SPRING_SCAFFOLDINGS,
        v.getSensors()
    ));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Double>> buildVoxelPropertiesCombinations(
      List<Pair<String, Pair<DoubleRange, Integer>>> properties
  ) {
    if (properties.size() == 0) {
      return List.of(Map.of());
    }
    List<String> propertyNames = properties.stream().map(Pair::first).toList();
    Set<Double>[] sets = properties.stream()
        .map(p -> (p.first().equals("friction")) ? FRICTION_VALUES : sampleRange(p.second().first(), p.second().second())
        )
        .toArray(Set[]::new);
    Set<List<Double>> values = Sets.cartesianProduct(sets);
    List<Map<String, Double>> propertiesCombinations = new ArrayList<>();
    for (List<Double> propertiesValues : values) {
      propertiesCombinations.add(
          IntStream.range(0, propertyNames.size()).boxed().collect(Collectors.toMap(
                  propertyNames::get, propertiesValues::get
              )
          )
      );
    }
    return propertiesCombinations;
  }

  private List<Map<String, Double>> buildVoxelPropertiesCombinations(
      List<String> propertyNames, List<List<String>> ranges
  ) {
    List<Pair<String, Pair<DoubleRange, Integer>>> properties = new ArrayList<>();
    for (int i = 0; i < propertyNames.size(); i++) {
      if (ranges.get(i) != null && ranges.get(i).size() == 3) {
        DoubleRange doubleRange = DoubleRange.of(Double.parseDouble(ranges.get(i).get(0)), Double.parseDouble(ranges.get(i).get(1)));
        int step = Integer.parseInt(ranges.get(i).get(2));
        properties.add(Pair.of(propertyNames.get(i), Pair.of(doubleRange, step)));
      }
    }
    return buildVoxelPropertiesCombinations(properties);
  }

  private Set<Double> sampleRange(DoubleRange range, int nSteps) {
    System.err.println("SampleRange");
    if (nSteps <= 2) {
      return Set.of();
    }
    double step = range.extent() / (nSteps - 1);
    return IntStream.range(0, nSteps).mapToObj(i -> range.min() + i * step).collect(Collectors.toSet());
  }

  private static final Set<Double> FRICTION_VALUES = Set.of(0.05, 0.1, 0.25, 0.6, 1.5, 3d, 10d, 25d);

}
