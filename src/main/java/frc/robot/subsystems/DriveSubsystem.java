package frc.robot.subsystems;

import com.ctre.phoenix.motorcontrol.NeutralMode;
import com.ctre.phoenix.motorcontrol.RemoteSensorSource;
import com.ctre.phoenix.sensors.PigeonIMU_StatusFrame;
import com.ctre.phoenix.motorcontrol.StatusFrameEnhanced;
import com.ctre.phoenix.motorcontrol.ControlMode;
import com.ctre.phoenix.motorcontrol.DemandType;
import com.ctre.phoenix.motorcontrol.FollowerType;
import com.ctre.phoenix.motorcontrol.FeedbackDevice;
import com.ctre.phoenix.motorcontrol.can.WPI_TalonSRX;
import com.ctre.phoenix.motorcontrol.can.WPI_VictorSPX;
import com.ctre.phoenix.sensors.PigeonIMU;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import edu.wpi.first.wpiutil.math.VecBuilder;
import edu.wpi.first.wpilibj2.command.RunCommand;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.RamseteCommand;
import edu.wpi.first.wpilibj.geometry.Transform2d;
import edu.wpi.first.wpilibj.geometry.Pose2d;
import edu.wpi.first.wpilibj.geometry.Rotation2d;
import edu.wpi.first.wpilibj.kinematics.DifferentialDriveOdometry;
import edu.wpi.first.wpilibj.simulation.AnalogGyroSim;
import edu.wpi.first.wpilibj.simulation.DifferentialDrivetrainSim;
import edu.wpi.first.wpilibj.simulation.EncoderSim;
import edu.wpi.first.wpilibj.smartdashboard.Field2d;
import edu.wpi.first.wpilibj.controller.ProfiledPIDController;
import edu.wpi.first.wpilibj.controller.SimpleMotorFeedforward;
import edu.wpi.first.wpilibj.controller.RamseteController;
import edu.wpi.first.wpilibj.trajectory.Trajectory;
import edu.wpi.first.wpilibj.trajectory.TrajectoryUtil;
import edu.wpi.first.wpilibj.trajectory.TrapezoidProfile;
import edu.wpi.first.wpilibj.trajectory.TrapezoidProfile.Constraints;
import edu.wpi.first.wpilibj.Filesystem;
import edu.wpi.first.wpilibj.RobotBase;
import edu.wpi.first.wpilibj.SpeedControllerGroup;
import edu.wpi.first.wpilibj.AnalogGyro;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.Encoder;
import edu.wpi.first.wpilibj.RobotController;

import java.nio.file.Paths;
import java.io.IOException;

import frc.robot.Constants.DriveConstants;
import io.github.oblarg.oblog.Loggable;
import io.github.oblarg.oblog.annotations.Config;
import io.github.oblarg.oblog.annotations.Log;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import frc.robot.sim.PhysicsSim;

public class DriveSubsystem extends SubsystemBase implements Loggable{
	// The motors on the left and right side of the drivetrain
	@Log
  private final WPI_TalonSRX m_talonsrxleft = new WPI_TalonSRX(DriveConstants.kLeftMotor2Port);
  
  private final WPI_VictorSPX m_victorspxleft = new WPI_VictorSPX(DriveConstants.kLeftMotor1Port);
  
	@Log
  private final WPI_TalonSRX m_talonsrxright = new WPI_TalonSRX(DriveConstants.kRightMotor2Port);
  
  private final WPI_TalonSRX m_talonsrxright2 = new WPI_TalonSRX(DriveConstants.kRightMotor1Port);

  // The motors on the left side of the drive.
  private final SpeedControllerGroup m_leftMotors =
        new SpeedControllerGroup(m_talonsrxleft, m_victorspxleft);

  // The motors on the right side of the drive.
  private final SpeedControllerGroup m_rightMotors =
        new SpeedControllerGroup(m_talonsrxright, m_talonsrxright2); 
        
  // The robot's drive
  //private final DifferentialDrive m_drive = new DifferentialDrive(m_leftMotors, m_rightMotors);

  // Pigeon is plugged into the second talon on the left side
  private final PigeonIMU m_pigeon = new PigeonIMU(m_talonsrxright2);
	
	// Odometry class for tracking robot pose
  private final DifferentialDriveOdometry m_odometry;

  // Using onboard feedforward since it is more accurate than Talon Feedforward
  private final SimpleMotorFeedforward m_driveFeedforward =
      new SimpleMotorFeedforward(DriveConstants.kS,
                                 DriveConstants.kV,
                                 DriveConstants.kA);

  // These classes help us simulate our drivetrain
  public DifferentialDrivetrainSim m_drivetrainSimulator;
  //private EncoderSim m_leftEncoderSim;
  //private EncoderSim m_rightEncoderSim;
  // The Field2d class simulates the field in the sim GUI. Note that we can have only one
  // instance!
  private Field2d m_fieldSim;

  private AnalogGyro m_gyro;
  private AnalogGyroSim m_gyroSim;

  /** Tracking variables */
	boolean _firstCall = false;
	boolean _state = false;
	double _targetAngle = 0;
  int _printCount = 0;
  private Pose2d savedPose;
  private Trajectory straightTrajectory;
  @Log
  double target_sensorUnits;

  // Turn PIDControllers
  Constraints turnconstraints = new TrapezoidProfile.Constraints(DriveConstants.kMaxSpeedMetersPerSecond,
                                             DriveConstants.kMaxAccelerationMetersPerSecondSquared);
  @Config
  ProfiledPIDController turnangle = new ProfiledPIDController(DriveConstants.kTurnP, DriveConstants.kTurnI, DriveConstants.kTurnD, turnconstraints);

  Constraints velocityconstraints = new TrapezoidProfile.Constraints(DriveConstants.kVelocityMaxSpeedMetersPerSecond,
                                             DriveConstants.kVelocityMaxAccelerationMetersPerSecondSquared);
  @Config
  ProfiledPIDController velocityleft = new ProfiledPIDController(DriveConstants.kVelocityP, DriveConstants.kVelocityI, DriveConstants.kVelocityD, velocityconstraints);

  @Config
  ProfiledPIDController velocityright = new ProfiledPIDController(DriveConstants.kVelocityP, DriveConstants.kVelocityI, DriveConstants.kVelocityD, turnconstraints);

  /**
   * Creates a new DriveSubsystem.
   */
  public DriveSubsystem() {
		m_odometry = new DifferentialDriveOdometry(Rotation2d.fromDegrees(getHeading()));

    // Simulation Setup
    if (RobotBase.isSimulation()) { // If our robot is simulated
      // This class simulates our drivetrain's motion around the field.
      m_drivetrainSimulator = new DifferentialDrivetrainSim(
          DriveConstants.kDrivetrainPlant,
          DriveConstants.kDriveGearbox,
          DriveConstants.kDriveGearing,
          DriveConstants.TRACK_WIDTH_METERS,
          DriveConstants.kWheelDiameterMeters / 2.0,
          VecBuilder.fill(0, 0, 0.0001, 0.1, 0.1, 0.005, 0.005));

      // The encoder and gyro angle sims let us set simulated sensor readings // Encoders are not real
      //m_leftEncoderSim = new EncoderSim(new Encoder(2, 3));
      //m_rightEncoderSim = new EncoderSim(new Encoder(4, 5));

      m_gyro = new AnalogGyro(1);
      m_gyroSim = new AnalogGyroSim(m_gyro);

      PhysicsSim.getInstance().addTalonSRX(m_talonsrxleft, 0.75, 5100, false);
      PhysicsSim.getInstance().addTalonSRX(m_talonsrxright, 0.75, 5100, false);

      // the Field2d class lets us visualize our robot in the simulation GUI.
      m_fieldSim = new Field2d();

      SmartDashboard.putData("Field", m_fieldSim);
    }

    // Set factory defaults
    m_talonsrxleft.configFactoryDefault();
    m_victorspxleft.configFactoryDefault();
    m_talonsrxright.configFactoryDefault();
    m_talonsrxright2.configFactoryDefault();

			// Set followers
    m_victorspxleft.follow(m_talonsrxleft);
		m_talonsrxright2.follow(m_talonsrxright);

    /* Disable all motor controllers */
		m_talonsrxright.set(0);
		m_talonsrxleft.set(0);
    
    // Set Ramping
    m_talonsrxleft.configClosedloopRamp(DriveConstants.kClosedRamp);
    m_talonsrxleft.configOpenloopRamp(DriveConstants.kOpenRamp);
    m_talonsrxright.configClosedloopRamp(DriveConstants.kClosedRamp);
    m_talonsrxright.configOpenloopRamp(DriveConstants.kOpenRamp);

		/* Set Neutral Mode */
		m_talonsrxleft.setNeutralMode(NeutralMode.Brake);
		m_talonsrxright.setNeutralMode(NeutralMode.Brake);
		
		/** Feedback Sensor Configuration */
		
		/* Configure the Pigeon IMU as a Remote Sensor for the right Talon */
		m_talonsrxright.configRemoteFeedbackFilter(m_pigeon.getDeviceID(),			// Device ID of Source
												RemoteSensorSource.GadgeteerPigeon_Yaw,	// Remote Feedback Source
												DriveConstants.REMOTE_1,				// Remote number [0, 1]
												DriveConstants.kTimeoutMs);			// Configuration Timeout
		
		/* Configure the Remote Sensor to be the Selected Sensor of the right Talon */
		m_talonsrxright.configSelectedFeedbackSensor(	FeedbackDevice.RemoteSensor1, 	// Set remote sensor to be used directly
													DriveConstants.PID_TURN, 			// PID Slot for Source [0, 1]
													DriveConstants.kTimeoutMs);			// Configuration Timeout
		
		/* Scale the Selected Sensor using a coefficient (Values explained in Constants.java */
		m_talonsrxright.configSelectedFeedbackCoefficient(	DriveConstants.kTurnTravelUnitsPerRotation / DriveConstants.kPigeonUnitsPerRotation,	// Coefficient
                            DriveConstants.PID_TURN, 														// PID Slot of Source
														DriveConstants.kTimeoutMs);														// Configuration Timeout
		/* Configure output and sensor direction */
		m_talonsrxleft.setInverted(false);
		m_talonsrxleft.setSensorPhase(true);
		m_talonsrxright.setInverted(true);
    m_talonsrxright.setSensorPhase(true);
    m_talonsrxright2.setInverted(true);
		
		/* Set status frame periods */
		m_talonsrxright.setStatusFramePeriod(StatusFrameEnhanced.Status_12_Feedback1, 20, DriveConstants.kTimeoutMs);
		m_talonsrxright.setStatusFramePeriod(StatusFrameEnhanced.Status_13_Base_PIDF0, 20, DriveConstants.kTimeoutMs);
		m_talonsrxright.setStatusFramePeriod(StatusFrameEnhanced.Status_14_Turn_PIDF1, 20, DriveConstants.kTimeoutMs);
		m_pigeon.setStatusFramePeriod(PigeonIMU_StatusFrame.CondStatus_9_SixDeg_YPR , 5, DriveConstants.kTimeoutMs);
		
		/* max out the peak output (for all modes).  However you can
		 * limit the output of a given PID object with configClosedLoopPeakOutput().
		 */
		m_talonsrxleft.configPeakOutputForward(+1.0, DriveConstants.kTimeoutMs);
		m_talonsrxleft.configPeakOutputReverse(-1.0, DriveConstants.kTimeoutMs);
		m_talonsrxright.configPeakOutputForward(+1.0, DriveConstants.kTimeoutMs);
		m_talonsrxright.configPeakOutputReverse(-1.0, DriveConstants.kTimeoutMs);

    /* FPID Gains for distance servo */
		m_talonsrxright.config_kP(DriveConstants.kSlot_Distanc, DriveConstants.kGains_Distanc.kP, DriveConstants.kTimeoutMs);
		m_talonsrxright.config_kI(DriveConstants.kSlot_Distanc, DriveConstants.kGains_Distanc.kI, DriveConstants.kTimeoutMs);
		m_talonsrxright.config_kD(DriveConstants.kSlot_Distanc, DriveConstants.kGains_Distanc.kD, DriveConstants.kTimeoutMs);
		m_talonsrxright.config_kF(DriveConstants.kSlot_Distanc, DriveConstants.kGains_Distanc.kF, DriveConstants.kTimeoutMs);
		m_talonsrxright.config_IntegralZone(DriveConstants.kSlot_Distanc, DriveConstants.kGains_Distanc.kIzone, DriveConstants.kTimeoutMs);
		m_talonsrxright.configClosedLoopPeakOutput(DriveConstants.kSlot_Distanc, DriveConstants.kGains_Distanc.kPeakOutput, DriveConstants.kTimeoutMs);

		/* FPID Gains for turn servo */
		m_talonsrxright.config_kP(DriveConstants.kSlot_Turning, DriveConstants.kGains_Turning.kP, DriveConstants.kTimeoutMs);
		m_talonsrxright.config_kI(DriveConstants.kSlot_Turning, DriveConstants.kGains_Turning.kI, DriveConstants.kTimeoutMs);
		m_talonsrxright.config_kD(DriveConstants.kSlot_Turning, DriveConstants.kGains_Turning.kD, DriveConstants.kTimeoutMs);
		m_talonsrxright.config_kF(DriveConstants.kSlot_Turning, DriveConstants.kGains_Turning.kF, DriveConstants.kTimeoutMs);
		m_talonsrxright.config_IntegralZone(DriveConstants.kSlot_Turning, DriveConstants.kGains_Turning.kIzone, DriveConstants.kTimeoutMs);
		m_talonsrxright.configClosedLoopPeakOutput(DriveConstants.kSlot_Turning, DriveConstants.kGains_Turning.kPeakOutput, DriveConstants.kTimeoutMs);
		m_talonsrxright.configAllowableClosedloopError(DriveConstants.kSlot_Turning, 0, DriveConstants.kTimeoutMs);	
		
		/* 1ms per loop.  PID loop can be slowed down if need be.
		 * For example,
		 * - if sensor updates are too slow
		 * - sensor deltas are very small per update, so derivative error never gets large enough to be useful.
		 * - sensor movement is very slow causing the derivative error to be near zero.
		 */
        final int closedLoopTimeMs = 1;
    m_talonsrxright.configClosedLoopPeriod(0, closedLoopTimeMs, DriveConstants.kTimeoutMs);
    m_talonsrxright.configClosedLoopPeriod(1, closedLoopTimeMs, DriveConstants.kTimeoutMs);

    /*
     * configAuxPIDPolarity(boolean invert, int timeoutMs) false means talon's local
     * output is PID0 + PID1, and other side Talon is PID0 - PID1 true means talon's
     * local output is PID0 - PID1, and other side Talon is PID0 + PID1
     */
    m_talonsrxright.configAuxPIDPolarity(false, DriveConstants.kTimeoutMs);

    /* Initialize */
    _firstCall = true;
    _state = false;
    _printCount = 0;
    zeroHeading(true);
  }

  public void resetOdometry(Pose2d pose) {
    setCurrentPose(pose);
  }

  /**
   * Resets the current pose to the specified pose. This should ONLY be called
   * when the robot's position on the field is known, like at the beginnig of
   * a match. This will also reset the saved pose since the old pose could be invalidated.
   * @param newPose new pose
   */
  public void setCurrentPose(Pose2d newPose) {
    resetEncoders();
    savedPose = newPose;
    m_odometry.resetPosition(newPose, Rotation2d.fromDegrees(getHeading()));
  }

  @Override
  public void periodic() {
    // Update the odometry in the periodic block
    if (RobotBase.isSimulation()) { // If our robot is simulated
      // This will get the simulated sensor readings that we set
      // in the previous article while in simulation, but will use
      // real values on the robot itself.
      m_odometry.update(m_gyro.getRotation2d(),
        -m_talonsrxleft.getSelectedSensorPosition() * DriveConstants.kEncoderDistancePerPulse,
        m_talonsrxright.getSelectedSensorPosition() * DriveConstants.kEncoderDistancePerPulse);
      m_fieldSim.setRobotPose(m_odometry.getPoseMeters());
    }
    else {
      m_odometry.update(
        Rotation2d.fromDegrees(getHeading()),
        m_talonsrxleft.getSelectedSensorPosition() * DriveConstants.kEncoderDistancePerPulse,
        m_talonsrxright.getSelectedSensorPosition() * DriveConstants.kEncoderDistancePerPulse);
      SmartDashboard.putString("Pose", m_odometry.getPoseMeters().toString());
    }
  }

  @Override
  public void simulationPeriodic() {
    // To update our simulation, we set motor voltage inputs, update the simulation,
    // and write the simulated positions and velocities to our simulated encoder and gyro.
    // We negate the right side so that positive voltages make the right side
    // move forward.
    m_drivetrainSimulator.setInputs(m_leftMotors.get() * RobotController.getBatteryVoltage(),
          m_rightMotors.get() * RobotController.getBatteryVoltage());
    m_drivetrainSimulator.update(0.020);

/*     m_leftEncoderSim.setDistance(m_drivetrainSimulator.getLeftPositionMeters());
    m_leftEncoderSim.setRate(m_drivetrainSimulator.getLeftVelocityMetersPerSecond());
    m_rightEncoderSim.setDistance(m_drivetrainSimulator.getRightPositionMeters());
    m_rightEncoderSim.setRate(m_drivetrainSimulator.getRightVelocityMetersPerSecond()); */

    m_talonsrxleft.getSimCollection().setQuadratureRawPosition((int)metersToSteps(m_drivetrainSimulator.getLeftPositionMeters()));
    m_talonsrxleft.getSimCollection().setQuadratureVelocity((int)metersPerSecToStepsPerDecisec(m_drivetrainSimulator.getLeftVelocityMetersPerSecond()));
    m_talonsrxright.getSimCollection().setQuadratureRawPosition((int)metersToSteps(m_drivetrainSimulator.getRightPositionMeters()));
    m_talonsrxright.getSimCollection().setQuadratureVelocity((int)metersPerSecToStepsPerDecisec(m_drivetrainSimulator.getRightVelocityMetersPerSecond()));

    m_gyroSim.setAngle(-m_drivetrainSimulator.getHeading().getDegrees());

    m_fieldSim.setRobotPose(getCurrentPose());

    PhysicsSim.getInstance().run();
  }

  public double getDrawnCurrentAmps() {
    return m_drivetrainSimulator.getCurrentDrawAmps();
  }

  /**
   * Returns the current wheel speeds of the robot.
   *
   * @return The current wheel speeds.
   */
  /* public DifferentialDriveWheelSpeeds getWheelSpeeds() {
    return new DifferentialDriveWheelSpeeds(
        m_talonsrxleft.getSelectedSensorVelocity() * DriveConstants.kEncoderDistancePerPulse,
        m_talonsrxright.getSelectedSensorVelocity() * DriveConstants.kEncoderDistancePerPulse);
  } */

  /**
   * Drives the robot using arcade controls.
   *
   * @param fwd the commanded forward movement
   * @param rot the commanded rotation
   */
  public void arcadeDrive(double fwd, double rot) {
    fwd = Deadband(fwd);
    rot = Deadband(rot);
    m_talonsrxleft.set(fwd + rot);
    m_talonsrxright.set(fwd - rot);
  }

   /**
   * Drives the robot using tank controls.
   *
   * @param left the commanded left side drivetrain power
   * @param right the commanded right side drivetrain power
   */
  public void tankDrive(double left, double right) {
    left = Deadband(left);
    right = Deadband(right);
    m_talonsrxleft.set(left);
    m_talonsrxright.set(right);
  }

  /**
   * Attempts to follow the given drive states using offboard PID. THIS IS UNTESTED
   *
   * @param left The left wheel state.
   * @param right The right wheel state.
   */
  public void setDriveStates(TrapezoidProfile.State left, TrapezoidProfile.State right) {
    m_talonsrxleft.set(ControlMode.Position,
                             left.position,
                             DemandType.ArbitraryFeedForward,
                             m_driveFeedforward.calculate(left.velocity));
    m_talonsrxright.set(ControlMode.Position,
                              right.position,
                              DemandType.ArbitraryFeedForward,
                              m_driveFeedforward.calculate(right.velocity));
  }

  /**
   * Resets the drive encoders to currently read a position of 0.
   */
  @Config
  public void resetEncoders() {
    m_talonsrxleft.setSelectedSensorPosition(0);
    m_talonsrxright.setSelectedSensorPosition(0);
  }

  /**
   * Gets the average distance of the two encoders.
   *
   * @return the average of the two encoder readings
   */
  @Log
  public double getAverageEncoderDistance() {
    if (!RobotBase.isSimulation()) {
      return (m_talonsrxleft.getSelectedSensorPosition() * DriveConstants.kEncoderDistancePerPulse
        + m_talonsrxright.getSelectedSensorPosition() * DriveConstants.kEncoderDistancePerPulse) / 2.0;
    }
    else {
      return 0;
    }
  }

  /** Deadband 15 percent, used on the gamepad */
  double Deadband(final double value) {
    /* Upper deadband */
    if (value >= +0.15)
      return value;

    /* Lower deadband */
    if (value <= -0.15)
      return value;

    /* Outside deadband */
    return 0;
  }

  /** Zero all sensors used. */
  @Config.ToggleButton
  void zeroHeading(boolean enabled) {
    m_pigeon.setYaw(0, DriveConstants.kTimeoutMs);
    m_pigeon.setAccumZAngle(0, DriveConstants.kTimeoutMs);
    System.out.println("[Pigeon] All sensors are zeroed.\n");
  }

  public Pose2d getCurrentPose() {
    return m_odometry.getPoseMeters();
  }

  public void saveCurrentPose() {
    savedPose = getCurrentPose();
  }

  public Pose2d getSavedPose() {
    return savedPose;
  }

  /**
   * Returns the heading of the robot.
   *
   * @return the robot's heading in degrees, from 180 to 180
   */
  @Log
  //@Log(tabName = "Dashboard", name = "Gyro Heading")
  public double getHeading() {
    final double[] ypr = new double[3];
		m_pigeon.getYawPitchRoll(ypr);
    return Math.IEEEremainder(ypr[0], 360);
  }

  @Log
  public double getTurnRate() {
    final double[] xyz = new double[3];
    m_pigeon.getRawGyro(xyz);
    return xyz[0];
  }

  public void tankDriveVelocity(double leftVelocity, double rightVelocity) {
    var leftAccel = (leftVelocity - stepsPerDecisecToMetersPerSec((int)m_talonsrxleft.getSelectedSensorVelocity())) / 20;
    var rightAccel = (rightVelocity - stepsPerDecisecToMetersPerSec((int)m_talonsrxright.getSelectedSensorVelocity())) / 20;
    
    var leftFeedForwardVolts = m_driveFeedforward.calculate(leftVelocity, leftAccel);
    var rightFeedForwardVolts = m_driveFeedforward.calculate(rightVelocity, rightAccel);

    m_talonsrxleft.set(
        ControlMode.Velocity, 
        metersPerSecToStepsPerDecisec(leftVelocity), 
        DemandType.ArbitraryFeedForward,
        (int)leftFeedForwardVolts / 12);
    m_talonsrxright.set(
        ControlMode.Velocity,
        metersPerSecToStepsPerDecisec(rightVelocity),
        DemandType.ArbitraryFeedForward,
        (int)rightFeedForwardVolts / 12);
  }

  /**
   * Creates a command to follow a Trajectory on the drivetrain.
   * @param trajectory trajectory to follow
   * @return command that will run the trajectory
   */
  public Command createCommandForTrajectory(String trajname) {
    try {
      straightTrajectory = loadTrajectory(trajname);
    } catch (IOException e) {
      DriverStation.reportError("Failed to load auto trajectory: " + trajname, false);
    }
    Transform2d transform = new Pose2d(0, 0, Rotation2d.fromDegrees(0)).minus(straightTrajectory.getInitialPose());
    Trajectory trajectory = straightTrajectory.transformBy(transform);
    this.resetOdometry(straightTrajectory.getInitialPose());
    return new RamseteCommand(
            trajectory,
            this::getCurrentPose,
            new RamseteController(DriveConstants.RAMSETE_B, DriveConstants.RAMSETE_ZETA),
            DriveConstants.kDriveKinematics,
            this::tankDriveVelocity,
            this);
  }

    /**
   * Converts from encoder steps to meters.
   * 
   * @param steps encoder steps to convert
   * @return meters
   */
  public static double stepsToMeters(int steps) {
    return (DriveConstants.WHEEL_CIRCUMFERENCE_METERS / DriveConstants.SENSOR_UNITS_PER_ROTATION) * steps;
  }

  /**
   * Converts from encoder units per 100 milliseconds to meters per second.
   * @param stepsPerDecisec steps per decisecond
   * @return meters per second
   */
  public static double stepsPerDecisecToMetersPerSec(int stepsPerDecisec) {
    return stepsToMeters(stepsPerDecisec * 10);
  }

  /**
   * Converts from meters to encoder units.
   * @param meters meters
   * @return encoder units
   */
  public static double metersToSteps(double meters) {
    return (meters / DriveConstants.WHEEL_CIRCUMFERENCE_METERS) * DriveConstants.SENSOR_UNITS_PER_ROTATION;
  }

  /**
   * Converts from meters per second to encoder units per 100 milliseconds.
   * @param metersPerSec meters per second
   * @return encoder units per decisecond
   */
  public static double metersPerSecToStepsPerDecisec(double metersPerSec) {
    return metersToSteps(metersPerSec) * .1d;
  }

  public WPI_TalonSRX getRightMaster() {
    return m_talonsrxright;
  }

  protected static Trajectory loadTrajectory(String trajectoryName) throws IOException {
    return TrajectoryUtil.fromPathweaverJson(
        Filesystem.getDeployDirectory().toPath().resolve(Paths.get("paths", trajectoryName + ".wpilib.json")));
  }

  // Drives straight specified distance in inches
  public void drivestraight(double distance) {
    target_sensorUnits = (distance * DriveConstants.SENSOR_UNITS_PER_ROTATION) / DriveConstants.WHEEL_CIRCUMFERENCE_INCHES ;
    m_talonsrxright.set(ControlMode.Position, target_sensorUnits, DemandType.AuxPID, m_talonsrxright.getSelectedSensorPosition(1));
		m_talonsrxleft.follow(m_talonsrxright, FollowerType.AuxOutput1);
  }

  // Turns to a specified angle using the cascading PID
  public void turnToAngle(double angle) {
    Double angleVelocity = turnangle.calculate(getHeading(), angle);
    tankDrive(velocityleft.calculate(m_talonsrxleft.getSelectedSensorVelocity(), -angleVelocity), velocityright.calculate(m_talonsrxright.getSelectedSensorVelocity(), angleVelocity));
  }

  // Turns to an angle relative to the current angle
  public void turnToRelativeAngle(double angle) {
    angle = 0;
  }

  // Sets up the talons to drive straightDistance with aux pid from Pigeon 
  public void distancesetup() {
    resetEncoders();
				
    /* Determine which slot affects which PID */
    m_talonsrxright.selectProfileSlot(DriveConstants.kSlot_Distanc, DriveConstants.PID_PRIMARY);
    m_talonsrxright.selectProfileSlot(DriveConstants.kSlot_Turning, DriveConstants.PID_TURN);
    m_talonsrxleft.follow(m_talonsrxright, FollowerType.AuxOutput1);
  }

  public void velocitysetup() {
    resetEncoders();
				
    /* Determine which slot affects which PID */
    m_talonsrxright.selectProfileSlot(DriveConstants.kSlot_Velocit, DriveConstants.PID_PRIMARY);
    m_talonsrxright.selectProfileSlot(DriveConstants.kSlot_Turning, DriveConstants.PID_TURN);
  }

  @Log
  public double getrighterror() {
    //return metersToSteps(m_talonsrxright.getClosedLoopError());
    return m_talonsrxright.getClosedLoopError();
  }

  @Log
  public double getlefterror() {
    return metersToSteps(m_talonsrxleft.getClosedLoopError());
  }

  @Log
  public boolean atSetpoint() {
    if (m_talonsrxright.getClosedLoopError() < 1000 && m_talonsrxright.getClosedLoopError() > 0){
      return true;
    } else {
      return false;
    }
  }

  public void stopmotors(boolean enabled) {
    m_talonsrxright.set(0);
    m_talonsrxleft.set(0);
  }

  @Log
  public void tankDriveVolts(final double leftVolts, final double rightVolts) {
    m_talonsrxleft.setVoltage(leftVolts);
    m_talonsrxright.setVoltage(rightVolts);
    //m_drive.feed();
  }

  public Command driveTime(double time, double speed) {
    return new RunCommand(() -> {m_talonsrxleft.set(speed);
      m_talonsrxright.set(speed);})
      .withTimeout(time)
      .andThen(() -> {m_talonsrxleft.set(0);m_talonsrxright.set(0);});
  }

  @Config
  public void drivePositionGyro(double distanceInches, double heading) {
    var sensorposition = heading * 10;
    distancesetup();
    target_sensorUnits = (distanceInches * DriveConstants.SENSOR_UNITS_PER_ROTATION) / DriveConstants.WHEEL_CIRCUMFERENCE_INCHES ;
    m_talonsrxright.set(ControlMode.Position, target_sensorUnits, DemandType.AuxPID, sensorposition);
  }

  @Config.ToggleButton
  public void drivePositionGyroTest(boolean enabled) {
    new RunCommand(() -> drivePositionGyro(120, getHeading())).withInterrupt(() -> atSetpoint()).withTimeout(5).schedule();
  }

  @Config.ToggleButton
  public void driveVelocityTest(boolean enabled) {
    new RunCommand(() -> tankDriveVelocity(.5, .5)).withTimeout(5).schedule();
  }
}