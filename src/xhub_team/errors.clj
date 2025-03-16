(ns xhub-team.errors)

(def request-format-error
 {:error-data (list  {:error_code 1 :error_message "Не верный формат запроса"})})

(def email-validate-error
  {:error-data (list {:error_code 2 :error_message "Введите корректную почту"})})

(def password-validate-error
  {:error-data (list {:error_code 3 :error_message "Введите корректный пароль"}) })

(defn error->aggregate [& errors]
  (reduce (fn [acc current-error]
            (merge-with (fn [old new] (concat old new))
                        acc
                        current-error))
          (if (sequential? errors) (first errors) errors)))
