package org.example;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.regex.Pattern;

public class Main {
    private static final Scanner scanner = new Scanner(System.in);
    private static final DatabaseHandler db = new DatabaseHandler();
    private static String currentUserEmail;

    public static void main(String[] args) {
        // Start the automatic savings scheduler
        db.startMonthlySavingsScheduler();
        
        // Ensure proper shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                db.shutdownScheduler();
                db.disconnectDatabase();
            } catch (SQLException e) {
                System.err.println("Shutdown error: " + e.getMessage());
            }
        }));

        while (true) {
            System.out.println("\n== Ledger System ==");
            System.out.println("Login or Register:");
            System.out.println("1. Login");
            System.out.println("2. Register");
            System.out.print("> ");

            String choice = scanner.nextLine();

            switch (choice) {
                case "1":
                    loginUser();
                    break;
                case "2":
                    registerUser();
                    break;
                default:
                    System.out.println("Invalid choice.\n");
            }
        }
    }

    private static void registerUser() {
        System.out.println("\n== Please fill in the form ==");

        String name;
        while (true) {
            System.out.print("Name: ");
            name = scanner.nextLine();
            if (isValidName(name)) break;
            System.out.println("Invalid name. Only letters and digits are allowed, no special characters.");
        }

        String email;
        while (true) {
            System.out.print("Email: ");
            email = scanner.nextLine();
            if (isValidEmail(email)) break;
            System.out.println("Invalid email format. Please try again.");
        }

        String password;
        while (true) {
            System.out.print("Password: ");
            password = scanner.nextLine();
            if (!isValidPassword(password)) {
                System.out.println("Password must be at least 8 characters contain at least one uppercase letter, one lowercase letter, one digit, and one special character.");
                continue;
            }

            System.out.print("Confirm Password: ");
            String confirmPassword = scanner.nextLine();

            if (!password.equals(confirmPassword)) {
                System.out.println("Passwords do not match. Try again.");
            } else {
                break;
            }
        }

        if (db.userExists(email)) {
            System.out.println("Email already registered!\n");
        } else {
            db.insertUser(name, email, password);
            System.out.println("\nRegister Successful!!!\n");
        }
    }

    // Name must be alphanumeric only
    private static boolean isValidName(String name) {
        return name.matches("^[a-zA-Z0-9]+$");
    }

    private static boolean isValidPassword(String password) {
        return password.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,}$");
    }

    private static boolean isValidEmail(String email) {
        String regex = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
        return Pattern.matches(regex, email);
    }

    public static void showUserSummary() {
        double balance = db.getBalance(currentUserEmail);
        double savings = db.getSavings(currentUserEmail);
        double loan = db.getLoanBalance(currentUserEmail);
        printUserSummary(currentUserEmail, balance, savings, loan);
    }

    public static void printUserSummary(String name, double balance, double savings, double totalRepayment) {
        System.out.println("\n== Welcome, " + name + " ==");
        System.out.printf(Locale.US, "Balance: %.2f\n", balance);
        System.out.printf(Locale.US, "Savings: %.2f\n", savings);
        System.out.printf(Locale.US, "Loan: %.2f\n", totalRepayment);
    }

    private static void loginUser() {
        System.out.println("\n== Please enter your email and password ==");

        String email;
        while (true) {
            System.out.print("Email: ");
            email = scanner.nextLine();
            if (isValidEmail(email)) break;
            System.out.println("Invalid email format. Please try again.");
        }

        System.out.print("Password: ");
        String password = scanner.nextLine();

        if (!db.userExists(email)) {
            System.out.println("Email not registered!\n");
        } else if (db.validateUser(email, password)) {
            System.out.println("\nLogin Successful!!!\n");

            currentUserEmail = email;
            db.checkLoanReminders(email);

            transactionMenu();
        } else {
            System.out.println("Incorrect password!\n");
        }
    }

    public static void transactionMenu() {
        try {
            Scanner input = new Scanner(System.in);
            int choice;
            while (true) {
                showUserSummary();
                System.out.println("\n== Transaction Menu ==");
                System.out.println("1.Debit");
                System.out.println("2.Credit");
                System.out.println("3.History Menu");
                System.out.println("4.Savings");
                System.out.println("5.Credit Loan");
                System.out.println("6.Deposit Interest Predictor");
                System.out.println("7.Data Visualization");
                System.out.println("8.Logout");
                System.out.print("> ");
                choice = input.nextInt();
                input.nextLine();

                switch (choice) {
                    case 1 -> handleDebit(input);
                    case 2 -> handleCredit(input);
                    case 3 -> {
                        System.out.println("\n== History Menu ==");
                        System.out.println("1.View Transaction History");
                        System.out.println("2.Filter and Sort");
                        System.out.println("3.Export to CSV");
                        System.out.println("4.Back");
                        System.out.print("> ");
                        int historyChoice = scanner.nextInt();
                        scanner.nextLine();

                        switch (historyChoice) {
                            case 1 -> db.showHistory(currentUserEmail);
                            case 2 -> filterHistory(currentUserEmail);
                            case 3 -> db.exportToCSV(currentUserEmail);
                            case 4 -> transactionMenu();
                            default -> System.out.println("Invalid.");
                        }
                    }
                    case 4 -> setupSavings();
                    case 5 -> creditLoan();
                    case 6 -> depositInterestPredictor();
                    case 7 -> db.exportVisualizationData();
                    case 8 -> {
                        System.out.println("Logging out...");
                        db.disconnectDatabase();
                        return;
                    }
                    default -> System.out.println("Invalid choice.");
                }
            }

        } catch (SQLException e) {
            System.err.println("Database error occurred.");
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("An unexpected error occurred.");
            e.printStackTrace();
        }
    }

    public static void handleCredit(Scanner input) {
        double balance = db.getBalance(currentUserEmail);

        // Check if blocked first
        int userId = db.getUserId(currentUserEmail);
        if (db.isBlocked(userId)) {
            System.out.println("Cannot perform transactions - you have overdue loans!");
            return;
        }

        System.out.println();
        System.out.println("==Credit==");
        System.out.print("Enter credit Amount: ");
        double amount = input.nextDouble();
        input.nextLine();
        System.out.print("Enter description: ");
        String desc = input.nextLine();

        if (amount <= 0 || amount > 1000000 || desc.length() > 100) {
            System.out.println("Invalid input.");
            return;
        }

        if (amount > balance) {
            System.out.println("Insufficient balance for this debit.");
            return;
        }

        balance -= amount;
        db.saveTransaction("Credit", amount, desc, currentUserEmail);

        // Process savings deduction
        db.processSavingsOnDebit(currentUserEmail, amount);
        System.out.println("Credit successfully recorded! Current balance: " + balance);
    }

    public static void handleDebit(Scanner input) {
        double balance = db.getBalance(currentUserEmail);

        // Check if blocked first
        int userId = db.getUserId(currentUserEmail);
        if (db.isBlocked(userId)) {
            System.out.println("Cannot perform transactions - you have overdue loans!");
            return;
        }

        System.out.println();
        System.out.println("==Debit==");
        System.out.print("Enter Debit Amount: ");
        double amount = input.nextDouble();
        input.nextLine();
        System.out.print("Enter description: ");
        String desc = input.nextLine();

        if (amount <= 0 || desc.length() > 100) {
            System.out.println("Invalid input.");
            return;
        }

        balance += amount;
        db.saveTransaction("Debit", amount, desc, currentUserEmail);
        System.out.println("Debit successfully recorded! Current balance: " + balance);
    }

    private static void setupSavings() {
        System.out.println("\n== Savings ==");
        System.out.print("Are you sure you want to activate it? (Y/N) : ");
        String confirm = scanner.nextLine().trim().toUpperCase();

        if (!confirm.equals("Y")) {
            return;
        }

        System.out.print("Please enter the percentage you wish to deduct from the next credit: ");
        int percentage = scanner.nextInt();
        scanner.nextLine();

        if (percentage < 1 || percentage > 100) {
            System.out.println("Percentage must be between 1 and 100");
            return;
        }

        db.activateSavings(currentUserEmail, percentage);
        System.out.println("Savings Settings added successfully!!!");
    }

    public static void creditLoan() {
        System.out.println("\n== Credit Loan ==");
        System.out.println("1. Apply for Loan");
        System.out.println("2. Make Loan Payment");
        System.out.println("3. Back");
        System.out.print("> ");
        int choice = scanner.nextInt();
        scanner.nextLine(); // consume newline
        System.out.println();

        switch (choice) {
            case 1 -> applyForLoan();
            case 2 -> repayLoan();
            case 3 -> transactionMenu();
            default -> System.out.println("Invalid choice.");
        }
    }

    private static void applyForLoan() {
        try {
            db.applyLoan(scanner, currentUserEmail);
            System.out.println("Loan application submitted successfully!");
        } catch (Exception e) {
            System.out.println("Error applying for loan: " + e.getMessage());
        }
    }

    private static void repayLoan() {
        System.out.println("== Repay Loan ==");
        db.repayLoan(scanner, currentUserEmail);
    }


    public static void depositInterestPredictor() {
        System.out.println("\n== Deposit Interest Predictor ==");
        Scanner scanner = new Scanner(System.in);
        System.out.print("Enter deposit amount: ");
        double deposit = scanner.nextDouble();

        if (deposit <= 0) {
            System.out.println("Deposit amount must be positive.");
            return;
        }

        System.out.println("Choose Bank: ");
        System.out.println("1. RHB (2.6%)");
        System.out.println("2. Maybank (2.5%)");
        System.out.println("3. Hong Leong (2.3%)");
        System.out.println("4. Alliance (2.85%)");
        System.out.println("5. AmBank (2.55%)");
        System.out.println("6. Standard Chartered (2.65%)");
        System.out.print("> ");
        int bank = scanner.nextInt();
        double rate;

        switch (bank) {
            case 1:
                rate = 2.6;
                break;
            case 2:
                rate = 2.5;
                break;
            case 3:
                rate = 2.3;
                break;
            case 4:
                rate = 2.85;
                break;
            case 5:
                rate = 2.55;
                break;
            case 6:
                rate = 2.65;
                break;
            default:
                System.out.println("Invalid");
                return;
        }

        double interestRate = (deposit * rate) / 12 / 100;
        System.out.printf(Locale.US, "\nMonthly interest earned: %.2f\n", interestRate);
    }

    public static void filterHistory(String currentUserEmail) throws SQLException {
        StringBuilder query = new StringBuilder("SELECT * FROM transactions WHERE user_email = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(currentUserEmail);

        // Date range filter
        System.out.print("\nFilter by date range? (Y/N): ");
        if (scanner.nextLine().trim().equalsIgnoreCase("Y")) {
            System.out.print("Start date (YYYY-MM-DD): ");
            String startDate = scanner.nextLine().trim();
            System.out.print("End date (YYYY-MM-DD): ");
            String endDate = scanner.nextLine().trim();
            query.append(" AND DATE(timestamp) BETWEEN ? AND ?");
            parameters.add(startDate);
            parameters.add(endDate);
        }

        // Transaction type
        System.out.print("Filter by transaction type (Debit/Credit)? (Y/N): ");
        if (scanner.nextLine().trim().equalsIgnoreCase("Y")) {
            System.out.print("Enter type (Debit/Credit): ");
            String type = scanner.nextLine().trim();
            query.append(" AND LOWER(type) = LOWER(?)");
            parameters.add(type);
        }

        // Amount range
        System.out.print("Filter by amount range? (Y/N): ");
        if (scanner.nextLine().trim().equalsIgnoreCase("Y")) {
            System.out.print("Minimum amount: ");
            double min = Double.parseDouble(scanner.nextLine().trim());
            System.out.print("Maximum amount: ");
            double max = Double.parseDouble(scanner.nextLine().trim());
            query.append(" AND amount BETWEEN ? AND ?");
            parameters.add(min);
            parameters.add(max);
        }

        // Sorting
        System.out.print("Sort results? (Y/N): ");
        if (scanner.nextLine().trim().equalsIgnoreCase("Y")) {
            System.out.print("Sort by (date/amount): ");
            String field = scanner.nextLine().trim().toLowerCase();
            System.out.print("Order (asc/desc): ");
            String order = scanner.nextLine().trim().toUpperCase();
            if (field.equals("date")) {
                query.append(" ORDER BY timestamp ").append(order);
            } else if (field.equals("amount")) {
                query.append(" ORDER BY amount ").append(order);
            }
        }

        try (PreparedStatement pstmt = db.getConnection().prepareStatement(query.toString())) {
            for (int i = 0; i < parameters.size(); i++) {
                pstmt.setObject(i + 1, parameters.get(i));
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                System.out.println("\nID | Type   | Amount       | Description          | Date");
                System.out.println("------------------------------------------------------------");
                while (rs.next()) {
                    System.out.printf(Locale.US, "%-2d | %-6s | %12.2f | %-20s | %s%n",
                            rs.getInt("id"),
                            rs.getString("type"),
                            rs.getDouble("amount"),
                            rs.getString("description"),
                            rs.getString("timestamp"));
                }
            }
        } catch (Exception e) {
            System.out.println("Error retrieving filtered history: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
