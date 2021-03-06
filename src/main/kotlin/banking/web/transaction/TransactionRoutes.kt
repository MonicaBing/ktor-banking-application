package banking.web.transaction

import banking.db.account.AccountTable
import banking.db.transaction.PayeeTransaction
import banking.db.transaction.PayeeTransactionTable
import banking.db.transaction.ReceiveTransaction
import banking.db.transaction.ReceiveTransactionTable
import banking.db.user.UserTable
import io.ktor.response.*
import io.ktor.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.ResultSet
import java.text.SimpleDateFormat
import java.util.*

// by username/email
fun Route.depositWithdrawByName() {
    route("/depositWithdrawByName") {
        post {
            val userName: String = this.context.request.queryParameters["userName"]!! // it cant be null
            val movement: String = this.context.request.queryParameters["movement"]!!
            val type: String = this.context.request.queryParameters["type"]!!


            val result: Boolean = transaction {
                val userId = UserTable.select { UserTable.userName eq userName }.singleOrNull()
                    ?.get(UserTable.id)?.value // ? = stop if null ie 2 conditions here

                return@transaction if (userId != null) {

                    AccountTable.update({ (AccountTable.userId eq userId) and (AccountTable.type eq type) }) {
                        with(SqlExpressionBuilder) {
                            it.update(AccountTable.balance, AccountTable.balance + movement.toDouble())
                        }
                    }
                    println("user found")
                    true
                } else {
                    println("user name doesn't exist, please provide a valid username")
                    false
                }
            }

            when (result) {
                true -> this.context.respond("success")
                false -> this.context.respond("failed")
            }
        }
    }
}

fun Route.paymentId() {
    route("/paymentId") {
        post {
            val payee: UUID = UUID.fromString(this.context.request.queryParameters["payee"]!!) // user id ->? not unique
            val receive: UUID = UUID.fromString(this.context.request.queryParameters["receive"]!!)
            val payment = this.context.request.queryParameters["payment"]!!
            val payeeType = this.context.request.queryParameters["payeeType"]!!
            val receiveType = this.context.request.queryParameters["receiveType"]!!

            transaction {
                AccountTable.update({ (AccountTable.userId eq payee) and (AccountTable.type eq payeeType) }) {
                    with(SqlExpressionBuilder) {
                        it.update(AccountTable.balance, AccountTable.balance - payment.toDouble())
                    }
                }
                AccountTable.update({ (AccountTable.userId eq receive) and (AccountTable.type eq receiveType) }) {
                    with(SqlExpressionBuilder) {
                        it.update(AccountTable.balance, AccountTable.balance + payment.toDouble())
                    }
                }

                val time: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
                val key: UUID = UUID.randomUUID()

                PayeeTransaction.new {
                    this.accId =
                        AccountTable.select { (AccountTable.userId eq payee) and (AccountTable.type eq payeeType) }
                            .single()[AccountTable.id].value
                    this.payment = -1 * payment.toDouble() // ------  negative value here
                    this.time = time
                    this.key = key
                }

                ReceiveTransaction.new {
                    this.accId =
                        AccountTable.select { (AccountTable.userId eq receive) and (AccountTable.type eq receiveType) }
                            .single()[AccountTable.id].value
                    this.payment = payment.toDouble() // ------  positive value here
                    this.time = time
                    this.key = key
                }
            }
            this.context.respond("success")
        }
    }
}

fun Route.paymentName() {
    route("/paymentName") {
        post {
            val payee: String = this.context.request.queryParameters["payee"]!!
            val receive: String = this.context.request.queryParameters["receive"]!!
            val payment = this.context.request.queryParameters["payment"]!!
            val payeeType = this.context.request.queryParameters["payeeType"]!!
            val receiveType = this.context.request.queryParameters["receiveType"]!!


            transaction {
                val payeeUserId = UserTable.select { UserTable.userName eq payee }.single()[UserTable.id].value
                val receiveUserId = UserTable.select { UserTable.userName eq receive }.single()[UserTable.id].value

                AccountTable.update({ (AccountTable.userId eq payeeUserId) and (AccountTable.type eq payeeType) }) {
                    with(SqlExpressionBuilder) {
                        it.update(AccountTable.balance, AccountTable.balance - payment.toDouble())
                    }
                }
                AccountTable.update({ (AccountTable.userId eq receiveUserId) and (AccountTable.type eq receiveType) }) {
                    with(SqlExpressionBuilder) {
                        it.update(AccountTable.balance, AccountTable.balance + payment.toDouble())
                    }
                }

                val time: String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
                val key: UUID = UUID.randomUUID()

                PayeeTransaction.new {
                    this.accId =
                        AccountTable.select {(AccountTable.userId eq payeeUserId) and (AccountTable.type eq payeeType)}
                            .single()[AccountTable.id].value
                    this.payment = payment.toDouble()
                    this.time = time
                    this.key = key
                }

                ReceiveTransaction.new {
                    this.accId =
                        AccountTable.select { (AccountTable.userId eq receiveUserId) and (AccountTable.type eq receiveType) }
                            .single()[AccountTable.id].value
                    this.payment = payment.toDouble()
                    this.time = time
                    this.key = key
                }
            }
            this.context.respond("success")
        }
    }
}

fun Route.netValue() {
    route("/netValue") {
        get {
            val result = mutableMapOf<UUID, Long>()
            transaction {
                val conn = TransactionManager.current().connection
                val query = """
                    select
                        summary.account_id,
                        sum(summary.payment)
                    from
                        (
                        select
                            *
                        from
                            receivetransaction r2
                        join account a on
                            a.account_id = r2.acc_id
                        where
                            a.user_id = ?
                    union
                        select
                            *
                        from
                            payeetransaction p2
                        join account a on
                            a.account_id = p2.acc_id
                        where
                            a.user_id = ? ) as summary
                    group by
                        summary.account_id
                """.trimIndent();
                val statement = conn.prepareStatement(query, false)
                statement.fillParameters(
                    listOf(
                        UUIDColumnType() to "66ebc2fc-2aa6-48db-b2cd-16e81250c50e", //refer to the ? above
                        UUIDColumnType() to "66ebc2fc-2aa6-48db-b2cd-16e81250c50e"
                    )
                );
                val resultSet: ResultSet = statement.executeQuery()
                while (resultSet.next()) {
                    result[UUID.fromString(resultSet.getString(1))] = resultSet.getLong(2)
                }
            }
            // return result
            this.context.respond(result)
        }
    }
}

// duisplay specfiically for one account
fun Route.displayTransaction() {
    route("/displayTransaction") {
        get {
            val accId = UUID.fromString(this.context.request.queryParameters["receive"]!!) // input
            // 5c934ceb-d797-453f-a53e-1fe641c2978b

            val result: List<ResultRow> = transaction {

                val result = ReceiveTransactionTable.select{ReceiveTransactionTable.accId eq accId}.toList()

//                val result = PayeeTransactionTable.select {
//                    PayeeTransactionTable.accId eq UUID.fromString(accountId)
//                }
//                // println(result.toList())
                return@transaction result
            }
            this.context.respond(result.map { //exactly what we should see in front end, test it on postman
                TransactionDto( // map it into the format that we want
                    it[ReceiveTransactionTable.accId],
                    it[ReceiveTransactionTable.payment]
                )
            })
        }
    }
}
// result == all -> feed it back to front end

// display for all accounts
// mapping in transactionDto -> class users
fun Route.displayUsers() {
    route("/displayUsers") {
        get {
            val result: List<ResultRow> = transaction {
                val result = UserTable.selectAll()

                return@transaction result.toList()
            }
            this.context.respond(result.map {
                UsersDto(
                    it[UserTable.id].value, // key
                    it[UserTable.name],
                    it[UserTable.userName]
                )
            })
        }
    }
}

fun Route.displayAccounts() {
    route("/displayAccounts") {
        get {
            //val userId = this.context.request.queryParameters["userId"]!! // fa2e3041-c130-4663-aa5e-4b4e5bad9bbe
            val userId = UUID.fromString(this.context.request.queryParameters["userId"]!!)

            val result: List<ResultRow> = transaction {

                // condition select all
                val userId = AccountTable.select {AccountTable.userId eq userId}.toList()


                // val result = AccountTable .selectAll()

                return@transaction userId
            }
            this.context.respond(result.map {
                AccountsDto(
                    it[AccountTable.id].value, // key
                    it[AccountTable.userId],
                    it[AccountTable.type],
                    it[AccountTable.balance]
                )
            })
            this.context.respond("success")
        }

    }
}

