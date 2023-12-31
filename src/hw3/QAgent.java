package hw3;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Arrays;

import edu.bu.hw3.linalg.Matrix;
import edu.bu.hw3.nn.LossFunction;
import edu.bu.hw3.nn.Model;
import edu.bu.hw3.nn.Optimizer;
import edu.bu.hw3.nn.layers.Dense;
import edu.bu.hw3.nn.layers.Sigmoid;
import edu.bu.hw3.nn.layers.ReLU;
import edu.bu.hw3.nn.layers.Tanh;
import edu.bu.hw3.nn.losses.MeanSquaredError;
import edu.bu.hw3.nn.models.Sequential;
import edu.bu.hw3.nn.optimizers.SGDOptimizer;
import edu.bu.hw3.streaming.Streamer;
import edu.bu.hw3.utils.Pair;
import edu.bu.hw3.utils.Triple;
import edu.cwru.sepia.action.Action;
import edu.cwru.sepia.action.ActionFeedback;
import edu.cwru.sepia.action.ActionResult;
import edu.cwru.sepia.action.TargetedAction;
import edu.cwru.sepia.agent.Agent;
import edu.cwru.sepia.environment.model.history.DamageLog;
import edu.cwru.sepia.environment.model.history.DeathLog;
import edu.cwru.sepia.environment.model.history.History.HistoryView;
import edu.cwru.sepia.environment.model.state.Unit;
import edu.cwru.sepia.environment.model.state.Unit.UnitView;
import edu.cwru.sepia.environment.model.state.State;
import edu.cwru.sepia.environment.model.state.State.StateView;
import edu.bu.hw3.nn.test.GradTest;



public class QAgent extends Agent
{

	public class XavierInitializer {
	    
	    private double scale;
	    
	    public XavierInitializer(int fanIn, int fanOut) {
	        this.scale = Math.sqrt(2.0 / (fanIn + fanOut));
	    }
	    
	    public double nextWeight() {
	        return scale * (2 * Math.random() - 1);
	    }
	}

	public class XavierRandom extends Random {
	    
	    private static final long serialVersionUID = 1L;
		private XavierInitializer initializer;
	    
	    public XavierRandom(int fanIn, int fanOut) {
	        this.initializer = new XavierInitializer(fanIn, fanOut);
	    }
	    
	    @Override
	    public double nextGaussian() {
	        return initializer.nextWeight();
	    }
	}

	public static final long serialVersionUID = -5077535504876086643L;
	public static final int RANDOM_SEED = 12345;
	public static final double GAMMA = 0.99;
	public static double LEARNING_RATE = 0.00003;
	public static final double EPSILON = 0.05; // prob of ignoring the policy and choosing a random action

	// our agent will play this many training episodes in a row before testing
	public static final int NUM_TRAINING_EPISODES_IN_BATCH = 10;

	// our agent will play this many testing episodes in a row before training again
	public static final int NUM_TESTING_EPISODES_IN_BATCH = 5;

	private final String paramFilePath;

	private Streamer streamer;

	private final int NUM_EPISODES_TO_PLAY;

	private int numTestEpisodesPlayedInBatch = -1;
	private int numTrainingEpisodesPlayed = 0;

	// rng to keep things repeatable...will combine with the RANDOM_SEED
	public final Random random;

	private Integer ENEMY_PLAYER_ID; // initially null until initialStep() is called

	private Set<Integer> myUnits;
	private Set<Integer> enemyUnits;
	private List<Double> totalRewards;
	private boolean loadSavedParams;
	private HashSet<Integer> prevDeadUnits;
	private HashMap<Integer,Integer> attackedUnits;

	/** NN specific things **/
	private Model qFunctionNN;
	private LossFunction lossFunction;
	private Optimizer optimizer;
	private int featureDim = 7;

	// how we remember what was the state, Q-value, and reward from the past
	private Map<Integer, Triple<Matrix, Matrix, Double> > oldInfoPerUnit;

	public QAgent(int playerId, String[] args)
	{
		super(playerId);
		System.out.println("Constructed my QAgent, playerID: " + this.playernum);
		String streamerArgString = null;
		String paramFilePath = null;

		if(args.length < 3)
		{
			System.err.println("QAgent.QAgent [ERROR]: need to specify playerId, streamerArgString, paramFilePath");
			System.exit(-1);
		}

		streamerArgString = args[1];
		paramFilePath = args[2];

		int numEpisodesToPlay = QAgent.NUM_TRAINING_EPISODES_IN_BATCH;
		boolean loadParams = false;
		if(args.length >= 4)
		{
			numEpisodesToPlay = Integer.parseInt(args[3]);
			if(args.length >= 5)
			{
				loadParams = Boolean.parseBoolean(args[4]);
			}
		}

		this.NUM_EPISODES_TO_PLAY = numEpisodesToPlay;
		this.ENEMY_PLAYER_ID = null; // initially

		this.paramFilePath = paramFilePath;
		this.loadSavedParams = loadParams;
		this.myUnits = null;
		this.enemyUnits = null;
		this.totalRewards = new ArrayList<Double>((int)this.NUM_EPISODES_TO_PLAY / QAgent.NUM_TRAINING_EPISODES_IN_BATCH);
		this.totalRewards.add(0.0);

		this.streamer = Streamer.makeDefaultStreamer(streamerArgString, this.getPlayerNumber());
		this.random = new Random(QAgent.RANDOM_SEED);

		this.qFunctionNN = this.initializeQFunction(loadParams);
		this.lossFunction = new MeanSquaredError();
		this.optimizer = new SGDOptimizer(this.getQFunction().getParameters(),
				QAgent.LEARNING_RATE);
		this.oldInfoPerUnit = new HashMap<Integer, Triple<Matrix, Matrix, Double> >();
		this.prevDeadUnits = new HashSet<Integer>();
		this.attackedUnits = new HashMap<Integer, Integer>();
		
	}

	private final String getParamFilePath() { return this.paramFilePath; }
	private Integer getEnemyPlayerId() { return this.ENEMY_PLAYER_ID; }
	private Set<Integer> getMyUnitIds() { return this.myUnits; }
	private Set<Integer> getEnemyUnitIds() { return this.enemyUnits; }
	private List<Double> getTotalRewards() { return this.totalRewards; }
	private final Streamer getStreamer() { return this.streamer; }
	private final Random getRandom() { return this.random; }

	/** NN specific stuff **/
	private Model getQFunction() { return this.qFunctionNN; }
	private LossFunction getLossFunction() { return this.lossFunction; }
	private Optimizer getOptimizer() { return this.optimizer; }
	private Map<Integer, Triple<Matrix, Matrix, Double> > getOldInfoPerUnit() { return this.oldInfoPerUnit; }
	
	private boolean isTrainingEpisode() { return this.numTestEpisodesPlayedInBatch == -1; }
	

	/**
	 * A method to create the neural network used for the Q function.
	 * You can make it as deep as you want to (although it will take more time to compute)
	 * 
	 * The API for creating a neural network is as follows:
	 *     Sequential m = new Sequential();
	 *     // layer 1
	 *     m.add(new Dense(feature_dim, hidden_dim1, this.getRandom()));
	 *     m.add(Sigmoid());
	 *     
	 *     // layer 2
	 *     m.add(new Dense(hidden_dim1, hidden_dim2, this.getRandom()));
	 *     m.add(Tanh());
	 *     
	 *     // add as many layers as you want
	 *     
	 *     // the last layer MUST be a scalar though
	 *     m.add(new Dense(hidden_dimN, 1));
	 *     m.add(ReLU()); // decide if you want to add an activation
	 * 
	 * @param loadParams
	 * @return
	 */
	private Model initializeQFunction(boolean loadParams)
	{
		Sequential m = new Sequential();
		/**
		 * TODO: create your model!
		 */
	    m.add(new Dense(this.featureDim, 10, this.getRandom()));
	    m.add(new Tanh());
	    m.add(new Dense(10, 8, this.getRandom()));
	    m.add(new Sigmoid());
	    m.add(new Dense(8, 10, this.getRandom()));
	    m.add(new Tanh());
	    m.add(new Dense(10, 1));
	    m.add(new Tanh());
	    
//		Random initializer = new XavierRandom(10, 1);
//	    m.add(new Dense(this.featureDim, 1,  initializer));

		if(loadParams)
		{
			try
			{
				m.load(this.getParamFilePath());
			} catch (Exception e)
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
				System.exit(-1);
			}
		}
		return m;
	}

	/**
     * Given the current state and the footman in question calculate the reward received on the last turn.
     * This is where you will check for things like Did this footman take or give damage? Did this footman die
     * or kill its enemy. Did this footman start an action on the last turn? 
     *
     * Remember that you will need to discount this reward based on the timestep it is received on.
     *
     * As part of the reward you will need to calculate if any of the units have taken damage. You can use
     * the history view to get a list of damages dealt in the previous turn. Use something like the following.
     *
     * for(DamageLog damageLogs : historyView.getDamageLogs(lastTurnNumber)) {
     *     System.out.println("Defending player: " + damageLog.getDefenderController() + " defending unit: " + \
     *         damageLog.getDefenderID() + " attacking player: " + damageLog.getAttackerController() + \
     *         "attacking unit: " + damageLog.getAttackerID());
     * }
     *
     * You will do something similar for the deaths. See the middle step documentation for a snippet
     * showing how to use the deathLogs.
     *
     * To see if a command was issued you can check the commands issued log.
     *
     * Map<Integer, Action> commandsIssued = historyView.getCommandsIssued(playernum, lastTurnNumber);
     * for (Map.Entry<Integer, Action> commandEntry : commandsIssued.entrySet()) {
     *     System.out.println("Unit " + commandEntry.getKey() + " was command to " + commandEntry.getValue().toString);
     * }
     *
     * @param state The current state of the game.
     * @param history History of the episode up until this turn.
     * @param unitId The id of the unit you are looking to calculate the reward for.
     * @return The current reward for that unit
     */
    private double getRewardForUnit(StateView state, HistoryView history, int unitId)
    {
    	/** TODO: complete me! **/
    	int KILL_REWARD = 50;
        int lastTurnNumber = state.getTurnNumber() - 1;

        // Initialize the reward to 0
        double reward = 0.0;
        if (state.getUnit(unitId) == null) {
        	//unit is dead
        	reward -= KILL_REWARD;
        }else {
	         for(DamageLog damageLog : history.getDamageLogs(lastTurnNumber)) {
		           if (damageLog.getDefenderID() == unitId) {
			           reward -= damageLog.getDamage();
		           }
		           if (damageLog.getAttackerID() == unitId) { 
		           //My unit is attacking enemy units
			           reward += damageLog.getDamage();
			           this.attackedUnits.put(unitId, damageLog.getDefenderID());
		           }
	           }
	
	         for (int unit : this.prevDeadUnits) { 
	        		 //Enemy's unit died
	        		 if (this.attackedUnits.get(unitId) != null) {
		        		 if (this.attackedUnits.get(unitId) == unit) { //If this myUnit contributes to enemy's unit death
		        			 reward += KILL_REWARD;
		        			 //System.out.println(unitId + " " + this.attackedUnits.get(unitId));
	        		 }
	        	 }
	         }
        }

//        // Discount the reward based on the timestep it was received
     
        reward *= Math.pow(QAgent.GAMMA, state.getTurnNumber() /100);

        
        return reward;
    }

    /**
    * Given a state and action calculate your features here. Please include a comment explaining what features
    * you chose and why you chose them.
    *
    * All of your feature functions should evaluate to a double. Collect all of these into a row vector
    * (a Matrix with 1 row and n columns). This will be the input to your neural network
    *
    * It is a good idea to make the first value in your array a constant. This just helps remove any offset
    * from 0 in the Q-function. The other features are up to you.
    * 
    * It might be a good idea to save whatever feature vector you calculate in the oldFeatureVectors field
    * so that when that action ends (and we observe a transition to a new state), we can update the Q value Q(s,a)
    *
    * @param state Current state of the SEPIA game
    * @param history History of the game up until this turn
    * @param atkUnitId Your unit. The one doing the attacking.
    * @param tgtUnitId An enemy unit. The one your unit is considering attacking.
    * @return The Matrix of feature function outputs.
    */
   

//   
//   private double [] accum = {0, 0, 0, 0, 0, 0, 0, 0, 0,};
//   private double [] accum_sq = {0, 0, 0, 0, 0, 0, 0, 0, 0,};
//   private double [] std = {1, 1, 1, 1, 1, 1, 1, 1, 1};
    private double[] maxValues = {0, 0, 0, 0, 0, 0, 0, 0,};
    private double[] minValues = {0, 0, 0, 0, 0, 0, 0, 0, };
   private int iterNum = 0;
   
   private Matrix calculateFeatureVector(StateView state, HistoryView history,
                                        int atkUnitId, int tgtUnitId)
   {
	   /** TODO: complete me! **/

//	    double[] maxValues = {1.0, Double.MIN_VALUE, Double.MIN_VALUE, Double.MIN_VALUE, Double.MIN_VALUE};
//	    double[] minValues = {0.0, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
	    // Initialize feature vector with constant term
	    Matrix featureVector = Matrix.zeros(1, this.featureDim); // initilizing a row vector with X columns
	    featureVector.set(0, 0, 1.0);
	    // Calculate distance between attacking unit and target unit
	    UnitView atkUnit = state.getUnit(atkUnitId);
	    UnitView tgtUnit = state.getUnit(tgtUnitId);
	    int xDiff = atkUnit.getXPosition() - tgtUnit.getXPosition();
	    int yDiff = atkUnit.getYPosition() - tgtUnit.getYPosition();
	    double distance = Math.sqrt(xDiff*xDiff + yDiff*yDiff);
	    featureVector.set(0, 1, distance);

	    // Add health features
	    featureVector.set(0, 2, atkUnit.getHP());
	    featureVector.set(0, 3, tgtUnit.getHP());
	    
	    // Add unit count feature
	    Set<Integer> unitIds = this.getMyUnitIds();
	    int unitCount = unitIds.size();
	    
	    // Add enemy count feature
	    Set<Integer> enemyIds = this.getEnemyUnitIds();
	    int enemyCount = enemyIds.size();
	    
	    //wolf pack feature
	    int sumPosX = 0;
	    int sumPosY = 0;
	    int posLength = 0;
	    for (int myU: this.getMyUnitIds()) {
	    	UnitView u = state.getUnit(myU);
	    	if (u != null) {
		    	sumPosX += u.getXPosition();
		    	sumPosY += u.getYPosition();
		    	posLength += 1;
	    	}
	    }
	    double centerX;
	    double centerY;
	    if (posLength > 0) {
		    centerX = sumPosX / posLength;
		    centerY = sumPosY / posLength;
	    }else {
	    	centerX = 0;
	    	centerY = 0;
	    }
	    
	    double distanceFromCenter = Math.sqrt((atkUnit.getXPosition() - centerX) * (atkUnit.getXPosition() - centerX) + (atkUnit.getYPosition() - centerY) * (atkUnit.getYPosition() - centerY));


    	double[] featureValues = {1.0, distance, atkUnit.getHP(),  tgtUnit.getHP(), unitCount, enemyCount, distanceFromCenter};
    	iterNum ++;
    	for (int i = 0; i < featureValues.length; i ++) {
    		if(featureValues[i] > maxValues[i]) {maxValues[i] = featureValues[i];}
    		if(featureValues[i] < minValues[i]) {minValues[i] = featureValues[i];}
    	}
    	
    	for (int i = 0; i < featureValues.length; i ++) { 
    		double input = (featureValues[i] - minValues[i]) / (maxValues[i] - minValues[i]);
    		featureVector.set(0, i, input);
    	}
    	
//    	for (int i = 0; i < featureValues.length; i ++ ) {
//    		this.accum[i] = (this.accum[i] + featureValues[i]);
//    		this.accum_sq[i] = this.accum_sq[i] + featureValues[i] * featureValues[i];
//    		if (iterNum > 0) {
//    			this.std[i] = Math.sqrt((iterNum * accum_sq[i] - (accum[i] * accum[i]) ) / iterNum * (iterNum - 1) );
//    		}
//    		double a = iterNum * accum_sq[i] - (accum[i] * accum[i]);
//    		
//  
//    	}
//    	
//    	for (int i = 0; i < featureValues.length; i ++ ) {
//    		double mean = this.accum[i] / iterNum;
//    		if (std[i] == 0) {featureVector.set(0, i, (featureValues[i] - mean) / mean );}
//    		else {
//    			//System.out.println("std " + std[i] + " " + (featureValues[i] - mean));
//    			featureVector.set(0, i, (featureValues[i] - mean) / std[i] );}
//    		//System.out.println("feartue: " + featureValues[i] + " mean: " + this.accum[i] / iterNum);
//    	}
//    	
//    	
//    	for (int i = 0; i < featureValues.length; i++) {
//		    double featureValue = featureValues[i];
//		    double maxFeatureValue = maxValues[i];
//		    double minFeatureValue = minValues[i];
//		    double normalizedFeatureValue = (featureValue - minFeatureValue) / (maxFeatureValue - minFeatureValue);
//		    featureValues[i] = normalizedFeatureValue;
//		    System.out.println("normal is " + normalizedFeatureValue);
//	    featureVector.set(0, i , normalizedFeatureValue);
//	}
	    
//    	double numbersFeature = 0.0;

//    	int lastTurnNumber = state.getTurnNumber() - 1;
//    	Map<Integer, Action> commandsIssued = history.getCommandsIssued(this.getEnemyPlayerId(), lastTurnNumber);
//    	
//    	for(Integer myFootman:this.myUnits){
//    		if(commandsIssued.containsKey(myFootman)){
//    			TargetedAction action = (TargetedAction) commandsIssued.get(myFootman);
//    			if(action.getTargetId() == tgtUnitId){
//    				numbersFeature += 1.0;
//    			}
//    		}
//    	}
//    	
//    	numbersFeature /= this.myUnits.size();
//    	
//    	featureVector.set(0, 4, numbersFeature);
    	
//
//	    // Add cooldown feature
//	    double atkUnitCooldown = atkUnit.getCooldown();
//	    if (atkUnitCooldown > 0) {
//	        featureVector.set(0, 4, atkUnitCooldown);
//	    }
//


    	
    	

//    	
//    	for (int i = 0; i < featureValues.length; i ++ ) {
//		featureVector.set(0, i, featureValues[i]);
//	}
//    	
	    // Return the feature vector
	    return featureVector;
   }
   
    /**
     * Calculate the Q-Value for a given state action pair. The state in this scenario is the current
     * state view and the history of this episode. The action is the attacker and the enemy pair for the
     * SEPIA attack action.
     *
     * This returns the Q-value according to your feature approximation. This is where you will pass
     * your features through your network (and extract the predicted q-value using the .item() method)
     * @param featureVec The feature vector
     * @return The approximate Q-value
     */
    private double calculateQValue(Matrix featureVec)
    {
    	double qValue = 0.0;
		
//    	if (iterNum % 809 == 0) {
//			LEARNING_RATE *= 0.99;
//			this.optimizer = new SGDOptimizer(this.getQFunction().getParameters(), QAgent.LEARNING_RATE);
//			//System.out.println("learning at " + this.optimizer.getLearningRate());
//		}
    	
        try
        {
			qValue = this.getQFunction().forward(featureVec).item();
//			System.out.println(this.totalRewards);
//			System.out.println("the q value is " + qValue);
//			System.out.println(featureVec);
		} catch (Exception e)
        {
			System.err.println("QAgent.caculateQValue [ERROR]: error in either forward() or item()");
			e.printStackTrace();
			System.exit(-1);
		}
        
//        try {
//	        GradTest.checkGrads(this.getQFunction(), this.lossFunction,  featureVec, , EPSILON, GAMMA);
//	        System.out.println("Gradients are correct!");
//	    } catch (Exception e) {
//	        System.out.println("Gradients are incorrect!");
//	        e.printStackTrace();
//	    }
        
        return qValue;
    }

    /**
     * Given a unit and the current state and history of the game select the enemy that this unit should
     * attack. This is where you would do the epsilon-greedy action selection.
     * 
     * You will need to consider who to attack. A unit should always be attacking
     * (if it is not currently attacking something), so what makes actions "different"
     * is who the unit is attacking
     *
     * @param state Current state of the game
     * @param history The entire history of this episode
     * @param atkUnitId The unit (your unit) that will be attacking
     * @return The enemy footman ID this unit should attack
     */
    private int selectAction(StateView state, HistoryView history, int atkUnitId)
    {
    	Integer tgtUnitId = null;
    	Matrix featureVec = null;
    	double maxQ = Double.NEGATIVE_INFINITY;
    	double r = this.getRewardForUnit(state, history, atkUnitId);

    	// epsilon-greedy (i.e. random exploration function)
    	if(this.getRandom().nextDouble() < QAgent.EPSILON && isTrainingEpisode() && !loadSavedParams)
    	{
    		System.out.println("Starting Random exploration!!");
    		// ignore policy and choose a random action (i.e. attacking which enemy)
    		int randomEnemyIdx = this.getRandom().nextInt(this.getEnemyUnitIds().size());

    		// get the unitId at that position
    		tgtUnitId = this.getEnemyUnitIds().toArray(new Integer[this.getEnemyUnitIds().size()])[randomEnemyIdx];
    		featureVec = this.calculateFeatureVector(state, history, atkUnitId, tgtUnitId);
    		maxQ = this.calculateQValue(featureVec);
    	} else
    	{
	    	// find the action (i.e. attacking which enemy) that maximizes the Q-value
	    	for(Integer enemyUnitId : this.getEnemyUnitIds())
	    	{
	    		Matrix features = this.calculateFeatureVector(state, history, atkUnitId, enemyUnitId);
	    		double qValue = this.calculateQValue(features);
	
	    		if(qValue > maxQ)
	    		{
	    			maxQ = qValue;
	    			featureVec = features;
	    			tgtUnitId = enemyUnitId;
	    		}
	    	}
    	}

    	// remember the info for this unit
    	this.getOldInfoPerUnit().put(atkUnitId, new Triple<Matrix, Matrix, Double>(featureVec, Matrix.full(1, 1, maxQ), r));

    	return tgtUnitId;
    }

    /**
     * This method calculates what the "true" Q(s,a) value should have been based on the Bellman equation for Q-values
     *
     * @param state The current state of the game
     * @param history The current history of the game
     * @param unitId The friendly unitId under consideration
     * @return
     */
    private Matrix getTDGroundTruth(StateView state, HistoryView history, int unitId) throws Exception
    {
    	if(!this.getOldInfoPerUnit().containsKey(unitId))
    	{
    		throw new Exception("unitId=" + unitId + " does not have an old feature vector...cannot calculate TD ground truth for it");
    	}
    	Triple<Matrix, Matrix, Double> oldInfo = this.getOldInfoPerUnit().get(unitId);
    	Double Rs = oldInfo.getThird();

    	double maxQ = Double.NEGATIVE_INFINITY;
    	if(state.getUnit(unitId) != null) {
    	// try all the actions (i.e. who to attack) in the current state
	    	for(Integer tgtUnitId: this.getEnemyUnitIds())
	    	{
	    		maxQ = Math.max(maxQ, this.calculateQValue(this.calculateFeatureVector(state, history, unitId, tgtUnitId)));
	    	}
    	}else {
    		maxQ = 0.0;
    	}
    	
    	return Matrix.full(1, 1, Rs + QAgent.GAMMA*maxQ); // output is always a scalar in active learning
    }

    /**
     * Calculate the updated weights for this agent. You should construct a matrix
     * @param r The reward R(s) for the prior state
     * @param state Current state of the game.
     * @param history History of the game up until this point
     * @param unitId The unit under consideration
     */
    private void updateParams(StateView state, HistoryView history, int unitId) throws Exception
    {
    	if(!this.getOldInfoPerUnit().containsKey(unitId))
    	{
    		throw new Exception("unitId=" + unitId + " does not have an old feature vector...cannot update params for it");
    	}
    	Triple<Matrix, Matrix, Double> oldInfo = this.getOldInfoPerUnit().get(unitId);
    	Matrix oldFeatureVector = oldInfo.getFirst();
    	Matrix Qsa = oldInfo.getSecond();

    	// reset the optimizer (i.e. reset gradients)
    	this.getOptimizer().reset();

    	// populate gradients
    	this.getQFunction().backwards(oldFeatureVector, this.getLossFunction().backwards(Qsa, this.getTDGroundTruth(state, history, unitId)));

    	// take a step in the correct direction
    	this.getOptimizer().step();
    }


	@Override
	public Map<Integer, Action> initialStep(StateView state, HistoryView history)
	{
		// find who our unitIDs are
		this.myUnits = new HashSet<Integer>();
		for(Integer unitId: state.getUnitIds(this.getPlayerNumber()))
		{
			UnitView unitView = state.getUnit(unitId);
			// System.out.println("Found new unit for player=" + this.getPlayerNumber() + " of type=" + unitView.getTemplateView().getName().toLowerCase() + " (id=" + unitId + ")");

			this.myUnits.add(unitId);
		}

		// find the enemy player
		Set<Integer> playerIds = new HashSet<Integer>();
		for(Integer playerId: state.getPlayerNumbers())
		{
			playerIds.add(playerId);
		}
		if(playerIds.size() != 2)
		{
			System.err.println("QAgent.initialStep [ERROR]: expected two players");
			System.exit(-1);
		}
		playerIds.remove(this.getPlayerNumber());
		this.ENEMY_PLAYER_ID = playerIds.iterator().next(); // get first element

		this.enemyUnits = new HashSet<Integer>();
		for(Integer unitId: state.getUnitIds(this.getEnemyPlayerId()))
		{
			UnitView unitView = state.getUnit(unitId);
			// System.out.println("Found new unit for player=" + this.getEnemyPlayerId() + " of type=" + unitView.getTemplateView().getName().toLowerCase() + " (id=" + unitId + ")");

			this.enemyUnits.add(unitId);
		}

		return this.middleStep(state, history);
	}

	/**
     * You will need to calculate the reward at each step and update your totals. You will also need to
     * check if an event has occurred. If it has then you will need to update your weights and select a new action.
     *
     * If you are using the footmen vectors you will also need to remove killed units. To do so use the historyView
     * to get a DeathLog. Each DeathLog tells you which player's unit died and the unit ID of the dead unit. To get
     * the deaths from the last turn do something similar to the following snippet. Please be aware that on the first
     * turn you should not call this as you will get nothing back.
     *
     * for(DeathLog deathLog : historyView.getDeathLogs(stateView.getTurnNumber() -1)) {
     *     System.out.println("Player: " + deathLog.getController() + " unit: " + deathLog.getDeadUnitID());
     * }
     *
     * You should also check for completed actions using the history view. Obviously you never want a footman just
     * sitting around doing nothing (the enemy certainly isn't going to stop attacking). So at the minimum you will
     * have an event whenever one your footmen's targets is killed or an action fails. Actions may fail if the target
     * is surrounded or the unit cannot find a path to the unit. To get the action results from the previous turn
     * you can do something similar to the following. Please be aware that on the first turn you should not call this
     *
     * Map<Integer, ActionResult> actionResults = historyView.getCommandFeedback(playernum, stateView.getTurnNumber() - 1);
     * for(ActionResult result : actionResults.values()) {
     *     System.out.println(result.toString());
     * }
     *
     * @return New actions to execute or nothing if an event has not occurred.
     */
	@Override
	public Map<Integer, Action> middleStep(StateView state, HistoryView history)
	{
		Map<Integer, Action> actions = new HashMap<Integer, Action>(this.getMyUnitIds().size());

    	// if this isn't the first turn in the game
    	if(state.getTurnNumber() > 0)
    	{

    		// check death logs and remove dead units
    		//removes all dead units from the set of unitIds
    		for(DeathLog deathLog : history.getDeathLogs(state.getTurnNumber() - 1))
    		{
    			this.prevDeadUnits.add(deathLog.getDeadUnitID());
    	
    			if(deathLog.getController() == this.getEnemyPlayerId())
    			{
    				this.getEnemyUnitIds().remove(deathLog.getDeadUnitID());
    			}
    		}
    	}

    	// get the previous action history in the previous step
		Map<Integer, ActionResult> prevUnitActions = history.getCommandFeedback(this.playernum, state.getTurnNumber() - 1);

    	for(Integer unitId : this.getMyUnitIds())
    	{
    		// decide what each unit should do (i.e. attack)

    		// calculate the reward for this unit
    		double reward = this.getRewardForUnit(state, history, unitId);
    		// if we are playing a test episode then add these rewards to the total reward for the test games
    		if(this.numTestEpisodesPlayedInBatch != -1)
    		{
    			this.totalRewards.set(this.totalRewards.size() - 1, 
  				this.totalRewards.get(this.totalRewards.size() - 1) + Math.pow(QAgent.GAMMA, state.getTurnNumber() /100) * reward);
//   			this.totalRewards.set(this.totalRewards.size() - 1, 
//       				this.totalRewards.get(this.totalRewards.size() - 1) + reward);
    		}
    		
//   			this.totalRewards.set(this.totalRewards.size() - 1, 
//       				this.totalRewards.get(this.totalRewards.size() - 1) + reward);	
//   			System.out.println(this.totalRewards);
    		
    		//if this unit does not have an action or the action was completed or failed...give a unit an action
    		if(state.getTurnNumber() == 0 || !prevUnitActions.containsKey(unitId) || 
    				prevUnitActions.get(unitId).getFeedback().equals(ActionFeedback.COMPLETED) ||
    				prevUnitActions.get(unitId).getFeedback().equals(ActionFeedback.FAILED))
    		{
    			if(state.getTurnNumber() > 0)
    			{
    				// we have arrived at a new state for that unit, so time to update some gradients
    				
    				try
    				{
						this.updateParams(state, history, unitId);
					} catch (Exception e)
    				{
						System.err.println("QAgent.middleStep [ERROR]: problem updating gradients for transition on unitId=" + unitId);
						e.printStackTrace();
						System.exit(-1);
					}
    			}
    			if(state.getUnit(unitId) != null)
    			{
    			     int tgtUnitId = this.selectAction(state, history, unitId);
    			    actions.put(unitId, Action.createCompoundAttack(unitId, tgtUnitId));
    			}
    		}
    	}

		// if this isn't the first turn in the game
		if(state.getTurnNumber() > 0)
		{
	
			// check death logs and remove dead units
			//removes all dead units from the set of unitIds
			for(DeathLog deathLog : history.getDeathLogs(state.getTurnNumber() - 1))
			{
		
				if(deathLog.getController() == this.getPlayerNumber())
				{
					this.getMyUnitIds().remove(deathLog.getDeadUnitID());
				}
			}
		}

    	if(actions.size() > 0)
    	{
    		this.getStreamer().streamMove(actions);
    	}
    	
    	this.prevDeadUnits.clear(); //clear prevDead history
    	this.attackedUnits.clear(); //clear attacked history
    	
        return actions;
	}

	@Override
	public void terminalStep(StateView state, HistoryView history)
	{
		if(this.isTrainingEpisode())
		{
			// save the model
			this.getQFunction().save(this.getParamFilePath());

			this.numTrainingEpisodesPlayed += 1;
			if((this.numTrainingEpisodesPlayed % QAgent.NUM_TRAINING_EPISODES_IN_BATCH) == 0)
			{
				this.numTestEpisodesPlayedInBatch = 0;
			}
		} else
		{
			
			this.numTestEpisodesPlayedInBatch += 1;
			if((this.numTestEpisodesPlayedInBatch % QAgent.NUM_TESTING_EPISODES_IN_BATCH) == 0)
			{
				this.numTestEpisodesPlayedInBatch = -1;
				// calculate the average
				this.getTotalRewards().set(this.getTotalRewards().size()-1,
						this.getTotalRewards().get(this.getTotalRewards().size()-1) / QAgent.NUM_TRAINING_EPISODES_IN_BATCH);
	
				// print the average test rewards
				this.printTestData(this.getTotalRewards());
				if(this.numTrainingEpisodesPlayed == this.NUM_EPISODES_TO_PLAY)
				{
					System.out.println("played all " + this.NUM_EPISODES_TO_PLAY + " games!");
					System.exit(0);
				} else
				{
					this.getTotalRewards().add(0.0);
				}
			}
		}
	}

	/**
     * DO NOT CHANGE THIS!
     *
     * Prints the learning curve data described in the assignment. Do not modify this method.
     *
     * @param averageRewards List of cumulative average rewards from test episodes.
     */
    private void printTestData (List<Double> averageRewards)
    {
        System.out.println("");
        System.out.println("Games Played      Average Cumulative Reward");
        System.out.println("-------------     -------------------------");
        for (int i = 0; i < averageRewards.size(); i++)
        {
            String gamesPlayed = Integer.toString(QAgent.NUM_TRAINING_EPISODES_IN_BATCH*(i+1));
            String averageReward = String.format("%.2f", averageRewards.get(i));

            int numSpaces = "-------------     ".length() - gamesPlayed.length();
            StringBuffer spaceBuffer = new StringBuffer(numSpaces);
            for (int j = 0; j < numSpaces; j++)
            {
                spaceBuffer.append(" ");
            }
            System.out.println(gamesPlayed + spaceBuffer.toString() + averageReward);
        }
        System.out.println("");
    }

	@Override
	public void loadPlayerData(InputStream inStream) {}

	@Override
	public void savePlayerData(OutputStream outStream) {}

}
