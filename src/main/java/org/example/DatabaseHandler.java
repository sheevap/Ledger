package org.example;

import java.io.FileWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.mindrot.jbcrypt.BCrypt;

public class DatabaseHandler {
    private static final String DB_URL = "jdbc:sqlite:ledger.db";
    static Connection conn;
    private ScheduledExecutorService scheduler;

    // initialize database table and connection
    static {
        try {
            conn = getConnection();
            createTables();

            System.out.println("Connected to SQLite database successfully.");
        } catch (SQLException e) {
            System.out.println("Error connecting to database: " + e.getMessage());
        }
    }

    public static Connection getConnection() throws SQLException {
        if (conn == null || conn.isClosed()) {
            conn = DriverManager.getConnection(DB_URL);
        }
        return conn;
    }

    public static void createTables() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS users (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "email TEXT NOT NULL UNIQUE, " +
                "password TEXT NOT NULL)";


        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);

            // transaction table
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    type TEXT NOT NULL,
                    amount REAL NOT NULL,
                    description TEXT NOT NULL,
                    user_email TEXT NOT NULL,
                    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_email) REFERENCES users(email)
                );
                """);

            // loans table
            stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS loans (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    user_email TEXT NOT NULL,
                    principal_amount REAL NOT NULL,
                    interest_rate REAL NOT NULL,
                    repayment_period INTEGER NOT NULL,
                    outstanding_balance REAL NOT NULL,
                    monthly_repayment REAL,
                    status TEXT NOT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                    next_payment_date DATE,
                    FOREIGN KEY (user_email) REFERENCES users(email)
                );
                """);

            // savings table
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS savings (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "user_email TEXT NOT NULL UNIQUE, " +
                    "percentage INTEGER NOT NULL, " +
                    "saved_amount REAL DEFAULT 0, " +
                    "FOREIGN KEY (user_email) REFERENCES users(email))");

        } catch (SQLException e) {
            System.out.println("Error creating tables: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public boolean userExists(String email) {
        String sql = "SELECT email FROM users WHERE email = ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {

            pstmt.setString(1, email);
            ResultSet rs = pstmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            System.out.println("Error checking user: " + e.getMessage());
            return false;
        }
    }

    public void insertUser(String name, String email, String password) {
        String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt()); // Hashing password
        String sql = "INSERT INTO users(name, email, password) VALUES(?,?,?)";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {

            pstmt.setString(1, name);
            pstmt.setString(2, email);
            pstmt.setString(3, hashedPassword);
            pstmt.executeUpdate();
            System.out.println("User inserted successfully.");
        } catch (SQLException e) {
            System.out.println("Error inserting user: " + e.getMessage());
        }
    }

    public boolean validateUser(String email, String password) {
        String sql = "SELECT password FROM users WHERE email = ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {

            pstmt.setString(1, email);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                String storedHash = rs.getString("password");
                return BCrypt.checkpw(password, storedHash); // Check bcrypt hash
            }
        } catch (SQLException e) {
            System.out.println("Error validating user: " + e.getMessage());
        }
        return false;
    }

    public static void showHistory(String email) {
        System.out.println("\n== Transaction History ==");

        String sql = "SELECT * FROM transactions WHERE user_email = ? ORDER BY timestamp DESC";

        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, email);
            ResultSet rs = stmt.executeQuery();

            System.out.println("ID | Type   | Amount       | Description          | Date");
            System.out.println("-------------------------------------------------------------");

            while (rs.next()) {
                System.out.printf(Locale.US, "%-2d | %-6s | %12.2f | %-20s | %s\n",
                        rs.getInt("id"),
                        rs.getString("type"),
                        rs.getDouble("amount"),
                        rs.getString("description"),
                        rs.getString("timestamp"));
            }
        } catch (SQLException e) {
            System.err.println("Error retrieving transaction history:");
            e.printStackTrace();
        }
    }


    public static void saveTransaction(String type, double amount, String description, String email) {
        String sql = "INSERT INTO transactions(type, amount, description, user_email) VALUES(?,?,?,?)";

        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, type);
            ps.setDouble(2, amount);
            ps.setString(3, description);
            ps.setString(4, email);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("Error saving transaction:");
            e.printStackTrace();
        }
    }

    public static void checkLoanReminders(String email) {
        String query = "SELECT created_at, repayment_period, outstanding_balance FROM loans WHERE user_email = ? AND status = 'active'";

        try (PreparedStatement ps = getConnection().prepareStatement(query)) {
            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();

            java.time.LocalDate today = java.time.LocalDate.now();
            boolean hasReminder = false;

            while (rs.next()) {
                java.sql.Timestamp timestamp = rs.getTimestamp("created_at");
                java.time.LocalDate createdDate = timestamp.toLocalDateTime().toLocalDate();

                int months = rs.getInt("repayment_period");
                java.time.LocalDate dueDate = createdDate.plusMonths(months);

                if (!dueDate.isBefore(today)) { // show upcoming loan
                    double balance = rs.getDouble("outstanding_balance");
                    System.out.printf(Locale.US, "Reminder: %.2f loan is due on %s\n", balance, dueDate);
                    hasReminder = true;
                }
            }

            if (!hasReminder) {
                System.out.println("No loan repayments due within the next 7 days.");
            }

        } catch (Exception e) {
            System.out.println("Error checking loan reminders: " + e.getMessage());
            e.printStackTrace();
        }
    }

    static void exportToCSV (String email) {
        String outputFile = "transaction_history.csv";

        String sql = "SELECT timestamp, description, type, amount FROM transactions WHERE user_email = ?";

        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {

            ps.setString(1, email);
            ResultSet rs = ps.executeQuery();

            try (FileWriter fw = new FileWriter(outputFile)) {
                // Write CSV headers
                fw.write("Date,Description,Type,Amount\n");

                while (rs.next()) {
                    String row = String.format(Locale.US, "%s,%s,%s,%.2f\n",
                            rs.getString("timestamp"),
                            rs.getString("description").replace(",", ";"),  // Handle commas in description
                            rs.getString("type"),
                            rs.getDouble("amount"));
                    fw.write(row);
                }

                System.out.println("\nSuccessfully exported transactions to " + outputFile);
            }
        } catch (Exception e) {
            System.out.println("Error exporting to CSV: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ====== Savings Auto-Deduction ======
    public void startMonthlySavingsScheduler() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // Calculate initial delay until next month's last day
        long initialDelay = calculateDaysUntilMonthEnd();
        
        // Schedule daily checks with initial delay
        scheduler.scheduleAtFixedRate(() -> {
            if (isLastDayOfMonth()) {
                transferSavingsToBalance();
            }
        }, initialDelay, 1, TimeUnit.DAYS);
    }

    private boolean isLastDayOfMonth() {
        LocalDate today = LocalDate.now();
        return today.getDayOfMonth() == today.lengthOfMonth();
    }

    private long calculateDaysUntilMonthEnd() {
        LocalDate today = LocalDate.now();
        LocalDate lastDay = today.withDayOfMonth(today.lengthOfMonth());
        return ChronoUnit.DAYS.between(today, lastDay);
    }

    private void transferSavingsToBalance() {
        String sql = "SELECT user_email, saved_amount FROM savings WHERE saved_amount > 0";
        
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            conn.setAutoCommit(false);
            
            while (rs.next()) {
                String userEmail = rs.getString("user_email");
                double amount = rs.getDouble("saved_amount");
                
                // Transfer to balance
                saveTransaction("Credit", amount, "Monthly savings transfer", userEmail);
                
                // Reset savings
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE savings SET saved_amount = 0 WHERE user_email = ?")) {
                    ps.setString(1, userEmail);
                    ps.executeUpdate();
                }

                System.out.println("Transferred " + amount + " from savings to balance");
            }
            conn.commit();
        } catch (SQLException e) {
            System.err.println("Error during savings transfer: " + e.getMessage());
            try {
                conn.rollback();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
        }
    }

    public void shutdownScheduler() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // ====== Savings Activation ======
    public void activateSavings(String userEmail, int percentage) {
        // First check if savings exists for user
        String checkSql = "SELECT 1 FROM savings WHERE user_email = ?";
        String insertSql = "INSERT INTO savings (user_email, percentage) VALUES (?, ?)";
        String updateSql = "UPDATE savings SET percentage = ? WHERE user_email = ?";
        
        try {
            // Check if record exists
            boolean exists = false;
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, userEmail);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    exists = rs.next();
                }
            }
            
            // Insert or update accordingly
            if (exists) {
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setInt(1, percentage);
                    updateStmt.setString(2, userEmail);
                    updateStmt.executeUpdate();
                }
            } else {
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, userEmail);
                    insertStmt.setInt(2, percentage);
                    insertStmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.err.println("Error activating savings: " + e.getMessage());
        }
    }

    public void processSavingsOnDebit(String userEmail, double debitAmount) {
        String sql = "SELECT percentage FROM savings WHERE user_email = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userEmail);
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                int percentage = rs.getInt("percentage");
                double savingsAmount = debitAmount * (percentage / 100.0);
                
                // Add to savings
                try (PreparedStatement updateStmt = conn.prepareStatement(
                        "UPDATE savings SET saved_amount = saved_amount + ? WHERE user_email = ?")) {
                    updateStmt.setDouble(1, savingsAmount);
                    updateStmt.setString(2, userEmail);
                    updateStmt.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.err.println("Error processing savings: " + e.getMessage());
        }
    }

    public double getSavings(String userEmail) {
        String sql = "SELECT saved_amount FROM savings WHERE user_email = ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, userEmail);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() ? rs.getDouble("saved_amount") : 0.0;
        } catch (SQLException e) {
            System.err.println("Error getting savings: " + e.getMessage());
            return 0.0;
        }
    }

    // ====== LOAN FUNCTIONALITY ======

    public int getUserId(String email) {
        String sql = "SELECT id FROM users WHERE email = ?";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, email);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() ? rs.getInt("id") : -1;
        } catch (SQLException e) {
            e.printStackTrace();
            return -1;
        }
    }

    public void applyLoan(Scanner scanner, String email) {
        System.out.print("Enter principal amount: ");
        double principal = scanner.nextDouble();

        System.out.print("Enter interest rate (e.g. 5 for 5%): ");
        double interestRate = scanner.nextDouble() / 100;

        System.out.print("Enter repayment period in months: ");
        int period = scanner.nextInt();

        double totalRepayment = principal * (1 + interestRate);
        double monthlyRepayment = totalRepayment / period;

        String sql = "INSERT INTO loans (user_email, principal_amount, interest_rate, repayment_period, " +
                "outstanding_balance, monthly_repayment, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'active')";

        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, email);
            pstmt.setDouble(2, principal);
            pstmt.setDouble(3, interestRate);
            pstmt.setInt(4, period);
            pstmt.setDouble(5, totalRepayment);
            pstmt.setDouble(6, monthlyRepayment);
            pstmt.executeUpdate();
            saveTransaction("Debit", principal, "Loan disbursement", email);
            System.out.println("Loan applied successfully. Total repayment: " + totalRepayment);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void repayLoan(Scanner scanner, String email) {
        String sql = "SELECT * FROM loans WHERE user_email = ? AND status = 'active' AND outstanding_balance > 0 AND monthly_repayment IS NOT NULL ORDER BY created_at DESC LIMIT 1";

        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, email);
            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) {
                System.out.println("No active loan to repay.");
                return;
            }

            int loanId = rs.getInt("id");
            double balance = rs.getDouble("outstanding_balance");
            double months = rs.getDouble("monthly_repayment");
            double repaymentAmount = Math.min(balance, months);

            conn.setAutoCommit(false);
            try {
                // Insert a debit transaction for repayment
                String insertTransaction = "INSERT INTO transactions (type, amount, description, user_email) " +
                        "VALUES ('Credit', ?, 'Loan repayment', ?)";
                try (PreparedStatement txnStmt = conn.prepareStatement(insertTransaction)) {
                    txnStmt.setDouble(1, repaymentAmount);
                    txnStmt.setString(2, email);
                    txnStmt.executeUpdate();
                }

                // Update loan balance and possibly status
                double newBalance = balance - repaymentAmount;
                String updateLoan = "UPDATE loans SET outstanding_balance = ?, status = ? WHERE id = ?";
                try (PreparedStatement updLoan = conn.prepareStatement(updateLoan)) {
                    updLoan.setDouble(1, newBalance);
                    updLoan.setString(2, (newBalance <= 0.01) ? "repaid" : "active");
                    updLoan.setInt(3, loanId);
                    updLoan.executeUpdate();
                }

                conn.commit();
                System.out.println("Repayment of " + repaymentAmount + " successful.");
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
                System.out.println("Error during repayment.");
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public double getLoanBalance(String email) {
        String sql = "SELECT COALESCE(SUM(outstanding_balance), 0) FROM loans WHERE user_email = ? AND status = 'active' AND outstanding_balance > 0";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sql)) {
            pstmt.setString(1, email);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() ? rs.getDouble(1) : 0.0;
        } catch (SQLException e) {
            System.err.println("Error getting loan balance: " + e.getMessage());
            return 0.0;
        }
    }

    public boolean isBlocked(int userId) {
        String sql = "SELECT * FROM loans WHERE user_email = ? AND status = 'active' AND outstanding_balance > 0 AND created_at <= date('now', '-repayment_period months')";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setInt(1, userId);
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public double getBalance(String email) {
        String sql = "SELECT SUM(CASE " +
                "WHEN type = 'Credit' THEN -amount " +
                "WHEN type = 'Debit' THEN amount " +
                "ELSE 0 END) AS balance " +
                "FROM transactions WHERE user_email = ?";
        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("balance");
                }
            }
        } catch (SQLException e) {
            System.err.println("Error fetching balance:");
            e.printStackTrace();
        }
        return 0.0;
    }

    public static void exportVisualizationData() {
        try (Statement stmt = conn.createStatement()) {
            // Create views for Power BI
            stmt.execute("CREATE VIEW IF NOT EXISTS vw_spending_trends AS " +
                    "SELECT date(timestamp) AS day, SUM(amount) AS total, type " +
                    "FROM transactions WHERE type = 'Debit' " +
                    "GROUP BY day, type");

            stmt.execute("CREATE VIEW IF NOT EXISTS vw_savings_growth AS " +
                    "SELECT date(timestamp) AS day, SUM(amount) AS savings_balance " +
                    "FROM transactions WHERE description LIKE '%savings%' " +
                    "GROUP BY day");

            stmt.execute("CREATE VIEW IF NOT EXISTS vw_loan_repayments AS " +
                    "SELECT date(timestamp) AS day, SUM(amount) AS payment " +
                    "FROM transactions WHERE description LIKE '%loan repayment%' " +
                    "GROUP BY day");

            stmt.execute("CREATE VIEW IF NOT EXISTS vw_spending_categories AS " +
                    "SELECT " +
                    "  CASE " +
                    "    WHEN description LIKE '%food%' THEN 'Food' " +
                    "    WHEN description LIKE '%rent%' THEN 'Housing' " +
                    "    WHEN description LIKE '%transport%' THEN 'Transport' " +
                    "    ELSE 'Other' " +
                    "  END AS category, " +
                    "  SUM(amount) AS amount " +
                    "FROM transactions WHERE type = 'Debit' " +
                    "GROUP BY category");

            System.out.println("\nViews for visualization created. You can now use Power BI to access them.");

        } catch (SQLException e) {
            System.err.println("Error creating visualization views: " + e.getMessage());
        }
    }

    public static void disconnectDatabase() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
            conn = null;
            System.out.println("Database connection closed.");
        }
    }
}
