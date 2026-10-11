# InstancePilot

Java-based SAP BusinessObjects report-instance monitoring, rerun verification, and lifecycle automation.

**Developed by:** Sai Teja Guddanti  
**Focus:** BI Automation & Administration

Questions, feedback, or bug reports:

- Email: saitejaguddanti999@gmail.com
- X: https://x.com/SaiTeja06779506
- LinkedIn: https://www.linkedin.com/in/sai-teja-628082288

## Safety notice

> **Test in a non-production environment first.** Do not enable live mode until you have reviewed the source code, validated the workflow, and confirmed that backups and recovery procedures are available.

InstancePilot can request report reruns and delete report instances when live mode is enabled. Detecting a replacement instance ID does **not** prove that the replacement report completed successfully or that its output is correct. Verify the replacement status and report output independently in SAP BusinessObjects CMC before trusting the automation in production.

Use this tool only on SAP BusinessObjects systems you are authorized to administer. You are responsible for configuration, reviewing destructive actions, and maintaining recovery procedures. The project is provided as-is, without guarantees.

## Features

- Monitors running SAP BusinessObjects report instances.
- Identifies instances that meet the configured duration threshold.
- Records activity in logs and state files.
- Requests a rerun and checks for a newly appearing replacement instance.
- Applies a configurable daily rerun limit.
- Displays startup information and a safety reminder in the terminal.

> These features describe the intended workflow. Verify behavior in your own environment before relying on it.

## Example Console Output

InstancePilot displays color-coded console messages to make monitoring and troubleshooting easier. The exact colors and messages may vary depending on the terminal, implementation, and runtime events.

### Color Legend

| Color | Meaning |
|---|---|
| 🟦 Cyan | Application banner and informational details |
| 🟢 Green | Successful operations or confirmations |
| 🟡 Yellow | Warnings, thresholds, or actions requiring attention |
| 🔴 Red | Errors or failed operations |
| ⚪ White / Gray | General status messages and supplementary details |

*The color meanings above are a suggested convention. They should match the actual console colors implemented in InstancePilot.*

### Illustrative Output

```text
================================================
             INSTANCEPILOT
  SAP BUSINESSOBJECTS AUTOMATION
================================================

[INFO] Application startup
[INFO] Connecting to SAP BusinessObjects CMS
[SUCCESS] CMS connection established
[INFO] Monitoring report instances
[WARNING] Instance exceeded configured duration threshold
[INFO] Rerun request submitted
[INFO] Checking for a replacement instance
[SUCCESS] Replacement instance detected
[INFO] Monitoring cycle completed
```

**Important:** This output is illustrative, not a verified transcript. A submitted rerun request or detected replacement instance does not guarantee successful report completion. Verify the final instance status and report output in SAP BusinessObjects CMC.

### Reading the Output

- **INFO:** Provides progress and monitoring details.
- **SUCCESS:** Indicates that the specific operation reported success; verify the actual outcome where necessary.
- **WARNING:** Highlights a condition that may need attention.
- **ERROR:** Indicates a failure requiring investigation.

InstancePilot should be tested in a non-production environment before enabling live automation or destructive instance-lifecycle actions.

## Screenshots

### SAP BusinessObjects CMC — Running Instances

Shows report instances visible in SAP BusinessObjects Central Management Console.

<img width="2559" height="1404" alt="image" src="https://github.com/user-attachments/assets/5c4d2e1a-854d-4075-86ab-9e3affb06474" />



### Build and Startup

Shows compilation output, the InstancePilot startup banner, and CMS login output.

<img width="1443" height="1079" alt="build-and-startup" src="https://github.com/user-attachments/assets/996f3974-4927-4746-b41a-40ff1bf8d102" />

### InstancePilot — Live Automation Output

Example terminal output showing instance detection, rerun requests, replacement-instance identification, cleanup messages, and daily-limit enforcement.

<img width="1715" height="1394" alt="automation-live-output" src="https://github.com/user-attachments/assets/4f3766c4-6986-43a7-91fb-dc65e21a14a9" />


## Requirements

- A JDK with `javac` and `java` available on `PATH`.
- SAP BusinessObjects Java SDK libraries installed locally.
- An authorized **non-production** SAP BusinessObjects account for initial testing.

The batch files may contain a local SAP BO Java library path. Update `SAP_LIB` in `build.bat` and `run.bat` to match your installation.

## Setup

1. Clone or download the repository.
2. Copy `config.example.json` to `config.json` and configure it for a non-production CMS and test account. Ensure the actual configuration format matches the implementation in the source code.
3. Keep live/destructive actions disabled during initial testing.
4. Run `build.bat` and proceed only if it prints `Build OK`.
5. Run `run.bat` and review the console output, logs, and CMC behavior.
6. Independently verify that replacement reports complete successfully and produce correct output before considering live mode.
7. Review the source and confirm a recovery plan before enabling any destructive action.

## How to Use InstancePilot

Follow these steps to run InstancePilot in an authorized, non-production SAP BusinessObjects environment.

### Step 1: Download the Project

Clone the repository and open the project directory:

```bash
git clone https://github.com/saiteja537/InstancePilot.git
cd InstancePilot
```

Alternatively, download the repository as a ZIP file from GitHub and extract it.

### Step 2: Configure SAP BusinessObjects Connection

Create your configuration file by copying the example:

```bat
copy config.example.json config.json
```

Open `config.json` and update the settings for your non-production SAP BusinessObjects environment.

| Setting | Description |
|---|---|
| `cms` | CMS hostname or address and port, for example `cms-test.example.com:6400` |
| `auth` | Authentication type supported by your implementation |
| `username` | Authorized test account username |
| `password` | Password for the test account |
| `threshold_minutes` | Duration threshold used to identify instances for review |
| `interval_minutes` | Interval between monitoring cycles |
| `live_mode` | Keep `false` during initial testing |
| `max_reruns_per_day` | Maximum configured rerun requests per day |
| `log_file` | Log file path |
| `state_file` | Runtime state file path |
| `snapshot_dir` | Directory for snapshots, if supported by the implementation |

Use values appropriate for your environment. Confirm the configuration options against the current source code before running the application.

### Step 3: Configure the Java SDK Path

Open `build.bat` and `run.bat`.

Update the `SAP_LIB` variable in both files to point to the Java library directory of your SAP BusinessObjects installation.

For example:

```bat
set "SAP_LIB=C:\SAP BusinessObjects\SAP BusinessObjects Enterprise XI 4.0\java\lib"
```

Use the actual path on your system. Ensure that a compatible JDK is installed and that both `java` and `javac` are available on your `PATH`.

### Step 4: Build the Application

Run the build script from the project directory:

```bat
build.bat
```

If compilation succeeds, the script should display:

```text
Build OK. Classes in: out\
```

If the build fails, verify the JDK installation, SAP SDK library path, and availability of the required Java source files.

### Step 5: Start InstancePilot

Run the application:

```bat
run.bat
```

Review the startup messages and confirm that the application can connect to the configured SAP BusinessObjects CMS.

Keep the terminal open while monitoring runs. Review any connection errors, authentication failures, warnings, or unexpected lifecycle actions.

### Step 6: Review Monitoring Results

During testing:

1. Observe the report instances being monitored.
2. Check whether instances exceeding the configured duration threshold are identified correctly.
3. Review the console output and configured log and state files.
4. If a rerun is requested, open SAP BusinessObjects Central Management Console (CMC) and inspect the resulting instance.
5. Verify the replacement instance's final status and report output independently.

A successful rerun request or detection of a replacement instance does not guarantee successful report execution.

### Step 7: Validate Before Enabling Live Mode

Keep `live_mode` set to `false` during initial testing.

Before enabling live or destructive operations:

- Review the source code and understand the instance-selection and deletion logic.
- Validate the complete workflow using test reports.
- Confirm that backups and recovery procedures are available.
- Verify rerun limits and ensure that only intended instances can be affected.
- Confirm that report outputs are correct after reruns.
- Obtain the required operational approval.

Only enable live mode after completing these checks. Treat instance deletion as a destructive operation.

### Troubleshooting

| Problem | What to check |
|---|---|
| Build fails | JDK installation, `SAP_LIB` path, and Java source files |
| CMS connection fails | CMS hostname, port, network connectivity, and authentication settings |
| No instances are detected | Account permissions, instance visibility, and monitoring configuration |
| Rerun fails | CMS connectivity, report permissions, and console error messages |
| Replacement instance is not confirmed | Inspect the instance history and final execution status in CMC |
| Unexpected behavior | Stop the application, preserve relevant logs, and investigate before restarting |

### Operational Reminder

Use InstancePilot only on SAP BusinessObjects systems you are authorized to administer. Test in a non-production environment first, protect credentials, and review all potentially destructive operations before enabling live automation.

## Logs and Troubleshooting

InstancePilot prints runtime messages to the console when you launch the application using `run.bat`. These messages help you monitor report-instance checks, rerun requests, replacement-instance detection, and lifecycle actions.

### Where to view logs

- **Live output:** Open the terminal running `run.bat` and review the messages displayed there.
- **Saved logs:** If your configuration or source code writes logs to files, open the configured log directory and review the relevant log file. The default saved-log path must be confirmed against your implementation.
- **Troubleshooting:** Check the console output for CMS connection errors, authentication failures, scheduling or rerun errors, and unexpected instance-lifecycle actions.

### Recommended log contents

For easier troubleshooting, logs should include timestamps, operation names, success or failure status, and relevant error messages. Do not log passwords, session cookies, authentication tokens, or other secrets.

> **Important:** A successful rerun request or detection of a replacement instance does not guarantee that the report completed successfully or generated correct output. Validate the report status and results in a non-production environment before enabling destructive actions.



Disclaimer

InstancePilot is provided as-is, without guarantees. Use it only on SAP BusinessObjects systems you are authorized to administer. Administrators are responsible for reviewing the configuration, validating results, and controlling any destructive operations.

A successful rerun request or newly detected child instance is not equivalent to successful report completion or correct report output. Treat automated deletion as destructive. Validate the full workflow in non-production first and keep a way to recover deleted instances where possible.
